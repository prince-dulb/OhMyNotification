package io.github.prince_dulb.ohmynotification.phase0

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import io.github.prince_dulb.ohmynotification.data.OmnDatabase
import io.github.prince_dulb.ohmynotification.OmnApplication
import io.github.prince_dulb.ohmynotification.capture.ListenerRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MvpDatabaseStatsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SET_CONTROLLED_SOURCE_EXCLUSION) {
            val graph = (context.applicationContext as OmnApplication).graph
            runBlocking {
                withContext(Dispatchers.IO) {
                    graph.repository.setSourceExcluded(
                        CONTROLLED_SOURCE_PACKAGE,
                        intent.getBooleanExtra(EXTRA_EXCLUDED, false),
                    )
                }
            }
            resultCode = Activity.RESULT_OK
            resultData = JSONObject().put("status", "OK").toString()
            return
        }
        if (intent.action == ACTION_CANCEL_STATUS_NOTIFICATION) {
            val graph = (context.applicationContext as OmnApplication).graph
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.activeNotifications
                .filter { item -> item.notification.channelId == graph.statusNotificationController.channelId() }
                .forEach { item -> notificationManager.cancel(item.tag, item.id) }
            resultCode = Activity.RESULT_OK
            resultData = JSONObject().put("status", "OK").toString()
            return
        }
        if (intent.action != ACTION_DATABASE_STATS) return
        val result = runCatching {
            runBlocking { withContext(Dispatchers.IO) { readStats(context) } }
        }
        result.onSuccess { stats ->
            resultCode = Activity.RESULT_OK
            resultData = stats.toString()
        }.onFailure { failure ->
            resultCode = Activity.RESULT_CANCELED
            resultData = JSONObject()
                .put("status", "ERROR")
                .put("failureType", failure.javaClass.simpleName)
                .toString()
        }
    }

    private fun readStats(context: Context): JSONObject {
        val database = OmnDatabase.get(context).openHelper.readableDatabase
        val graph = (context.applicationContext as OmnApplication).graph
        val runtimeArgs = arrayOf(graph.runtimeSessionId)
        val activeStatus = context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { item -> item.notification.channelId == graph.statusNotificationController.channelId() }
            ?.notification
        val publicStatus = activeStatus?.publicVersion
        val latestPrivateText = database.latestPrivateText()
        val publicTextFields = publicStatus?.let(::visibleTextFields).orEmpty()
        return JSONObject()
            .put("status", "OK")
            .put("listenerConnected", ListenerRuntimeState.isConnected())
            .put(
                "statusNotificationPermissionGranted",
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            )
            .put(
                "statusNotificationChannelEnabled",
                graph.statusNotificationController.isChannelEnabled(),
            )
            .put(
                "statusNotificationActive",
                graph.statusNotificationController.isStatusNotificationActive(),
            )
            .put(
                "statusNotificationPublishedUpdateCount",
                graph.statusNotificationController.publishedUpdateCount(),
            )
            .put(
                "statusNotificationOngoing",
                activeStatus?.flags?.and(Notification.FLAG_ONGOING_EVENT) != 0,
            )
            .put(
                "statusNotificationOnlyAlertOnce",
                activeStatus?.flags?.and(Notification.FLAG_ONLY_ALERT_ONCE) != 0,
            )
            .put("statusNotificationPrivateVisibility", activeStatus?.visibility == Notification.VISIBILITY_PRIVATE)
            .put("statusNotificationPublicVersionPresent", publicStatus != null)
            .put("statusNotificationPublicVisibility", publicStatus?.visibility == Notification.VISIBILITY_PUBLIC)
            .put(
                "statusPublicContainsLatestTitle",
                latestPrivateText.title?.let { title -> publicTextFields.any { it.contains(title) } } == true,
            )
            .put(
                "statusPublicContainsLatestBody",
                latestPrivateText.body?.let { body -> publicTextFields.any { it.contains(body) } } == true,
            )
            .put(
                "includedSourceFilterCount",
                graph.inboxViewPreferencesStore.includedSourceKeys.value.size,
            )
            .put("excludedSourceCount", database.scalar("SELECT COUNT(*) FROM excluded_sources"))
            .put("policyExcludedSourceCount", graph.policyStore.snapshot().userExcludedPackages.size)
            .put(
                "controlledSourceExcluded",
                CONTROLLED_SOURCE_PACKAGE in graph.policyStore.snapshot().userExcludedPackages,
            )
            .put("runtimeActionHandleCount", graph.runtimeActionStore.size())
            .put("notificationItemCount", database.scalar("SELECT COUNT(*) FROM notification_items"))
            .put(
                "duplicateIdentityGenerationGroups",
                database.scalar(
                    """
                    SELECT COUNT(*) FROM (
                        SELECT 1 FROM notification_items
                        GROUP BY sourcePackage, sourceUserRef, systemKey, lifecycleGeneration
                        HAVING COUNT(*) > 1
                    )
                    """.trimIndent(),
                ),
            )
            .put(
                "removedItemCount",
                database.scalar("SELECT COUNT(*) FROM notification_items WHERE isRemoved = 1"),
            )
            .put("healthEvidenceCount", database.scalar("SELECT COUNT(*) FROM health_evidence"))
            .put(
                "listenerConnectedEvidenceCount",
                database.scalar("SELECT COUNT(*) FROM health_evidence WHERE kind = 'LISTENER_CONNECTED'"),
            )
            .put(
                "notificationCommitEvidenceCount",
                database.scalar("SELECT COUNT(*) FROM health_evidence WHERE kind = 'NOTIFICATION_COMMITTED'"),
            )
            .put(
                "currentRuntimeEvidenceCount",
                database.scalar(
                    "SELECT COUNT(*) FROM health_evidence WHERE runtimeSessionId = ?",
                    runtimeArgs,
                ),
            )
            .put(
                "currentRuntimeListenerConnectedEvidenceCount",
                database.scalar(
                    """
                    SELECT COUNT(*) FROM health_evidence
                    WHERE runtimeSessionId = ? AND kind = 'LISTENER_CONNECTED'
                    """.trimIndent(),
                    runtimeArgs,
                ),
            )
            .put(
                "currentRuntimeCommitEvidenceCount",
                database.scalar(
                    """
                    SELECT COUNT(*) FROM health_evidence
                    WHERE runtimeSessionId = ? AND kind = 'NOTIFICATION_COMMITTED'
                    """.trimIndent(),
                    runtimeArgs,
                ),
            )
            .put(
                "currentRuntimeDistinctCommittedItemCount",
                database.scalar(
                    """
                    SELECT COUNT(DISTINCT itemId) FROM health_evidence
                    WHERE runtimeSessionId = ? AND kind = 'NOTIFICATION_COMMITTED'
                    """.trimIndent(),
                    runtimeArgs,
                ),
            )
            .put("capturedFieldsChangedSincePreviousStats", capturedFieldChanges(database, runtimeArgs))
    }

    private fun capturedFieldChanges(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        runtimeArgs: Array<out Any?>,
    ): JSONArray {
        val current = database.query(
            """
            SELECT itemId, notificationId, tag, lifecycleGeneration,
                   firstReceivedAtEpochMillis, sortTimeEpochMillis, originalPostTimeEpochMillis,
                   title, body, sourceLabelSnapshot, contentFingerprint, normalizationWarnings,
                   normalizationStrategyVersion, titleOriginalCodePoints, bodyOriginalCodePoints,
                   hadContentIntent, contentIntentCreatorPackage, notificationActionCount,
                   isRemoved, removedAtEpochMillis, lastRemovalReason
            FROM notification_items
            WHERE itemId = (
                SELECT itemId FROM health_evidence
                WHERE runtimeSessionId = ? AND kind = 'NOTIFICATION_COMMITTED'
                ORDER BY evidenceId DESC
                LIMIT 1
            )
            """.trimIndent(),
            runtimeArgs,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return JSONArray()
            CapturedFieldSnapshot(
                itemId = cursor.getLong(0),
                fingerprints = CAPTURED_FIELD_NAMES.mapIndexed { index, name ->
                    name to cursor.valueFingerprint(index + 1)
                }.toMap(),
            )
        }
        val previous = synchronized(CAPTURED_FIELD_SNAPSHOT_LOCK) {
            lastCapturedFieldSnapshot.also { lastCapturedFieldSnapshot = current }
        }
        if (previous == null || previous.itemId != current.itemId) return JSONArray()
        return JSONArray(
            CAPTURED_FIELD_NAMES.filter { name ->
                previous.fingerprints[name] != current.fingerprints[name]
            },
        )
    }

    private fun Cursor.valueFingerprint(index: Int): Int? = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(index).hashCode()
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index).hashCode()
        Cursor.FIELD_TYPE_STRING -> getString(index).hashCode()
        Cursor.FIELD_TYPE_BLOB -> getBlob(index).contentHashCode()
        else -> null
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.latestPrivateText(): LatestPrivateText =
        query(
            """
            SELECT title, body FROM notification_items
            ORDER BY sortTimeEpochMillis DESC, itemId DESC
            LIMIT 1
            """.trimIndent(),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return LatestPrivateText(null, null)
            LatestPrivateText(
                title = cursor.getStringOrNull(0),
                body = cursor.getStringOrNull(1),
            )
        }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun visibleTextFields(notification: Notification): List<String> = listOfNotNull(
        notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
    )

    private fun androidx.sqlite.db.SupportSQLiteDatabase.scalar(
        query: String,
        bindArgs: Array<out Any?> = emptyArray(),
    ): Long =
        query(query, bindArgs).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private companion object {
        val CAPTURED_FIELD_SNAPSHOT_LOCK = Any()
        val CAPTURED_FIELD_NAMES = listOf(
            "notificationId",
            "tag",
            "lifecycleGeneration",
            "firstReceivedAtEpochMillis",
            "sortTimeEpochMillis",
            "originalPostTimeEpochMillis",
            "title",
            "body",
            "sourceLabelSnapshot",
            "contentFingerprint",
            "normalizationWarnings",
            "normalizationStrategyVersion",
            "titleOriginalCodePoints",
            "bodyOriginalCodePoints",
            "hadContentIntent",
            "contentIntentCreatorPackage",
            "notificationActionCount",
            "isRemoved",
            "removedAtEpochMillis",
            "lastRemovalReason",
        )
        var lastCapturedFieldSnapshot: CapturedFieldSnapshot? = null
        const val ACTION_DATABASE_STATS =
            "io.github.prince_dulb.ohmynotification.debug.DATABASE_STATS"
        const val ACTION_CANCEL_STATUS_NOTIFICATION =
            "io.github.prince_dulb.ohmynotification.debug.CANCEL_STATUS_NOTIFICATION"
        const val ACTION_SET_CONTROLLED_SOURCE_EXCLUSION =
            "io.github.prince_dulb.ohmynotification.debug.SET_CONTROLLED_SOURCE_EXCLUSION"
        const val EXTRA_EXCLUDED = "excluded"
        const val CONTROLLED_SOURCE_PACKAGE = "io.github.prince_dulb.ohmynotification.test"
    }

    private data class CapturedFieldSnapshot(
        val itemId: Long,
        val fingerprints: Map<String, Int?>,
    )

    private data class LatestPrivateText(val title: String?, val body: String?)
}
