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
    private val includedSources = MutableStateFlow(readIncludedSources())

    val includedSourceKeys: StateFlow<Set<AppUserKey>> = includedSources.asStateFlow()

    suspend fun setIncludedSources(sources: Set<AppUserKey>): Boolean = withContext(Dispatchers.IO) {
        val normalized = sources.toSet()
        synchronized(this@InboxViewPreferencesStore) {
            if (includedSources.value == normalized) return@withContext true
            val saved = preferences.edit()
                .putStringSet(
                    INCLUDED_SOURCE_KEYS,
                    normalized.mapTo(linkedSetOf(), InboxSourceKeyCodec::encode),
                )
                .commit()
            if (saved) includedSources.value = normalized
            saved
        }
    }

    private fun readIncludedSources(): Set<AppUserKey> {
        val encoded = preferences.getStringSet(INCLUDED_SOURCE_KEYS, null)
        if (encoded != null) {
            return encoded.mapNotNullTo(linkedSetOf(), InboxSourceKeyCodec::decode)
        }

        // Phase-0 builds stored package names only. Interpret them as the current Android user
        // until the user next applies the filter, then the exact composite keys are persisted.
        return preferences.getStringSet(LEGACY_INCLUDED_SOURCE_PACKAGES, emptySet())
            .orEmpty()
            .filter(String::isNotBlank)
            .mapTo(linkedSetOf()) { sourcePackage -> AppUserKey(currentUserRef, sourcePackage) }
    }

    private companion object {
        const val PREFERENCES_NAME = "omn-inbox-view"
        const val INCLUDED_SOURCE_KEYS = "included-source-keys-v1"
        const val LEGACY_INCLUDED_SOURCE_PACKAGES = "included-source-packages"
    }
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
