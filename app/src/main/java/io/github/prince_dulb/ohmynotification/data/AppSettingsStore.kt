package io.github.prince_dulb.ohmynotification.data

import android.content.Context
import io.github.prince_dulb.ohmynotification.core.model.AppSettingsSnapshot
import io.github.prince_dulb.ohmynotification.core.model.GroupingWindowOptions
import io.github.prince_dulb.ohmynotification.core.model.NotificationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AppSettingsStore(
    context: Context,
    private val policyStore: MonitoringPolicyStore,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(readSettings())

    val settings: StateFlow<AppSettingsSnapshot> = current.asStateFlow()

    init {
        policyStore.replaceRecordedNotificationTypes(current.value.recordedNotificationTypes)
    }

    suspend fun setNotificationTypeRecorded(type: NotificationType, recorded: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(this@AppSettingsStore) {
                val previous = current.value
                val types = previous.recordedNotificationTypes.toMutableSet().apply {
                    if (recorded) add(type) else remove(type)
                }.toSet()
                if (types == previous.recordedNotificationTypes) return@withContext true
                val saved = preferences.edit()
                    .putStringSet(RECORDED_TYPES_KEY, types.mapTo(linkedSetOf(), NotificationType::name))
                    .commit()
                if (saved) {
                    val updated = previous.copy(recordedNotificationTypes = types)
                    policyStore.replaceRecordedNotificationTypes(types)
                    current.value = updated
                }
                saved
            }
        }

    suspend fun setGroupingWindowMinutes(minutes: Int): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@AppSettingsStore) {
            val normalized = GroupingWindowOptions.normalize(minutes)
            val previous = current.value
            if (normalized == previous.groupingWindowMinutes) return@withContext true
            val saved = preferences.edit().putInt(GROUPING_WINDOW_KEY, normalized).commit()
            if (saved) current.value = previous.copy(groupingWindowMinutes = normalized)
            saved
        }
    }

    private fun readSettings(): AppSettingsSnapshot {
        val default = AppSettingsSnapshot.DEFAULT
        val types = if (preferences.contains(RECORDED_TYPES_KEY)) {
            preferences.getStringSet(RECORDED_TYPES_KEY, emptySet())
                .orEmpty()
                .mapNotNullTo(linkedSetOf()) { name ->
                    NotificationType.entries.firstOrNull { type -> type.name == name }
                }
        } else {
            default.recordedNotificationTypes
        }
        return AppSettingsSnapshot(
            recordedNotificationTypes = types,
            groupingWindowMinutes = GroupingWindowOptions.normalize(
                preferences.getInt(GROUPING_WINDOW_KEY, default.groupingWindowMinutes),
            ),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "omn-app-settings"
        const val RECORDED_TYPES_KEY = "recorded-notification-types-v1"
        const val GROUPING_WINDOW_KEY = "grouping-window-minutes-v1"
    }
}
