package io.github.prince_dulb.ohmynotification.data

import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class InboxViewPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val currentUserRef = Process.myUserHandle().toString()
    private val filterState = MutableStateFlow(readFilter())

    val filter: StateFlow<InboxViewFilter> = filterState.asStateFlow()

    suspend fun setIncludedSources(sources: Set<AppUserKey>): Boolean = withContext(Dispatchers.IO) {
        setFilter(InboxViewFilter(includedSources = sources.toSet()))
    }

    suspend fun setFilter(filter: InboxViewFilter): Boolean = withContext(Dispatchers.IO) {
        val normalized = filter.normalized()
        synchronized(this@InboxViewPreferencesStore) {
            if (filterState.value == normalized) return@withContext true
            val saved = preferences.edit()
                .putStringSet(
                    INCLUDED_SOURCE_KEYS,
                    normalized.includedSources.mapTo(linkedSetOf(), InboxSourceKeyCodec::encode),
                )
                .putStringSet(
                    EXCLUDED_SOURCE_KEYS,
                    normalized.excludedSources.mapTo(linkedSetOf(), InboxSourceKeyCodec::encode),
                )
                .commit()
            if (saved) filterState.value = normalized
            saved
        }
    }

    private fun readFilter(): InboxViewFilter {
        val encoded = preferences.getStringSet(INCLUDED_SOURCE_KEYS, null)
        if (encoded != null) {
            val included = encoded.mapNotNullTo(linkedSetOf(), InboxSourceKeyCodec::decode)
            val excluded = preferences.getStringSet(EXCLUDED_SOURCE_KEYS, emptySet())
                .orEmpty()
                .mapNotNullTo(linkedSetOf(), InboxSourceKeyCodec::decode)
            return InboxViewFilter(includedSources = included, excludedSources = excluded).normalized()
        }

        // Phase-0 builds stored package names only. Interpret them as the current Android user
        // until the user next applies the filter, then the exact composite keys are persisted.
        val legacyIncluded = preferences.getStringSet(LEGACY_INCLUDED_SOURCE_PACKAGES, emptySet())
            .orEmpty()
            .filter(String::isNotBlank)
            .mapTo(linkedSetOf()) { sourcePackage -> AppUserKey(currentUserRef, sourcePackage) }
        return InboxViewFilter(includedSources = legacyIncluded)
    }

    private companion object {
        const val PREFERENCES_NAME = "omn-inbox-view"
        const val INCLUDED_SOURCE_KEYS = "included-source-keys-v1"
        const val EXCLUDED_SOURCE_KEYS = "excluded-source-keys-v1"
        const val LEGACY_INCLUDED_SOURCE_PACKAGES = "included-source-packages"
    }
}

data class InboxViewFilter(
    val includedSources: Set<AppUserKey> = emptySet(),
    val excludedSources: Set<AppUserKey> = emptySet(),
) {
    init {
        require(includedSources.isEmpty() || excludedSources.isEmpty())
    }

    fun only(source: AppUserKey): InboxViewFilter =
        InboxViewFilter(includedSources = setOf(source))

    fun without(source: AppUserKey): InboxViewFilter = when {
        includedSources.isEmpty() -> copy(excludedSources = excludedSources + source)
        includedSources.size > 1 -> copy(includedSources = includedSources - source)
        else -> InboxViewFilter(excludedSources = setOf(source))
    }

    internal fun normalized(): InboxViewFilter = copy(
        includedSources = includedSources.toSet(),
        excludedSources = excludedSources.toSet(),
    )
}

internal object InboxSourceKeyCodec {
    private const val ENCODING_PREFIX = "v1:"

    fun encode(source: AppUserKey): String = buildString {
        append(ENCODING_PREFIX)
        append(source.sourceUserRef.length)
        append(':')
        append(source.sourceUserRef)
        append(source.sourcePackage)
    }

    fun decode(encoded: String): AppUserKey? {
        if (!encoded.startsWith(ENCODING_PREFIX)) return null
        val lengthEnd = encoded.indexOf(':', ENCODING_PREFIX.length)
        if (lengthEnd < 0) return null
        val userLength = encoded.substring(ENCODING_PREFIX.length, lengthEnd).toIntOrNull() ?: return null
        if (userLength <= 0) return null
        val userStart = lengthEnd + 1
        val packageStart = userStart + userLength
        if (packageStart >= encoded.length) return null
        val userRef = encoded.substring(userStart, packageStart)
        val sourcePackage = encoded.substring(packageStart)
        return runCatching { AppUserKey(userRef, sourcePackage) }.getOrNull()
    }
}
