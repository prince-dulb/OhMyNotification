package io.github.prince_dulb.ohmynotification.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class InboxViewPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val includedSources = MutableStateFlow(readIncludedSources())

    val includedSourcePackages: StateFlow<Set<String>> = includedSources.asStateFlow()

    suspend fun setIncludedSourcePackages(sourcePackages: Set<String>): Boolean = withContext(Dispatchers.IO) {
        val normalized = sourcePackages.filterTo(linkedSetOf()) { it.isNotBlank() }
        synchronized(this@InboxViewPreferencesStore) {
            if (includedSources.value == normalized) return@withContext true
            val saved = preferences.edit()
                .putStringSet(INCLUDED_SOURCE_PACKAGES, normalized)
                .commit()
            if (saved) includedSources.value = normalized
            saved
        }
    }

    private fun readIncludedSources(): Set<String> =
        preferences.getStringSet(INCLUDED_SOURCE_PACKAGES, emptySet()).orEmpty().toSet()

    private companion object {
        const val PREFERENCES_NAME = "omn-inbox-view"
        const val INCLUDED_SOURCE_PACKAGES = "included-source-packages"
    }
}
