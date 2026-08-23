package io.github.prince_dulb.ohmynotification.phase0

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.prince_dulb.ohmynotification.data.OmnDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class MvpDatabaseStatsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DATABASE_STATS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching { readStats(context) }
            result.onSuccess { stats ->
                pendingResult.resultCode = Activity.RESULT_OK
                pendingResult.resultData = stats.toString()
            }.onFailure { failure ->
                pendingResult.resultCode = Activity.RESULT_CANCELED
                pendingResult.resultData = JSONObject()
                    .put("status", "ERROR")
                    .put("failureType", failure.javaClass.simpleName)
                    .toString()
            }
            pendingResult.finish()
        }
    }

    private fun readStats(context: Context): JSONObject {
        val database = OmnDatabase.get(context).openHelper.readableDatabase
        return JSONObject()
            .put("status", "OK")
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
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.scalar(query: String): Long =
        query(query).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private companion object {
        const val ACTION_DATABASE_STATS =
            "io.github.prince_dulb.ohmynotification.debug.DATABASE_STATS"
    }
}
