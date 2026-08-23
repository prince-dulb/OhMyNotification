package io.github.prince_dulb.ohmynotification.testsource

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.StatusBarNotification

internal class ControlledNotificationSource(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun publish(caseId: String, isUpdate: Boolean = false) {
        val fixture = ControlledNotificationFixtures.load(context, caseId)
        ensureChannel()

        val targetIntent = Intent(context, ControlledTargetActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CASE_ID, fixture.caseId)
            putExtra(EXTRA_TARGET_TOKEN, fixture.targetToken ?: NO_ACTION_TOKEN)
        }
        val targetPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val phase = if (isUpdate) "update" else "publish"
        val displayTitle = buildString {
            append(MARKER)
            append(" · ")
            append(fixture.title ?: "title-missing")
            append(" · ")
            append(phase)
        }
        val displayText = fixture.text ?: "OMN_SYNTHETIC_TEXT_MISSING"
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(displayTitle)
            .setContentText(displayText)
            .setSubText(fixture.sourcePackage)
            // Android drops notifications whose displayed `when` is too old. Keep the
            // deterministic fixture time separately while posting with a current time.
            .setWhen(System.currentTimeMillis())
            .addExtras(
                Bundle().apply {
                    putLong(EXTRA_FIXTURE_POSTED_AT, fixture.postedAtEpochMillis)
                },
            )
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(targetPendingIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(isUpdate)
            .setOngoing(false)

        if (fixture.styleHint == "big_text") {
            builder.setStyle(Notification.BigTextStyle().bigText(displayText))
        }
        if (fixture.targetToken != null) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                    "Open synthetic target",
                    targetPendingIntent,
                ).build(),
            )
        }

        notificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build())
    }

    fun remove() {
        notificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
    }

    fun activeNotification(): StatusBarNotification? = notificationManager.activeNotifications
        .firstOrNull { notification ->
            notification.id == NOTIFICATION_ID && notification.tag == NOTIFICATION_TAG
        }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OMN Phase 0 controlled source",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Test-only synthetic notifications; never shipped in the production APK"
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_FIXTURE_POSTED_AT = "omn.synthetic.fixture_posted_at_epoch_millis"
        const val MARKER = "OMN_TEST_ONLY_NOTIFICATION"
        const val EXTRA_CASE_ID = "omn.synthetic.case_id"
        const val EXTRA_TARGET_TOKEN = "omn.synthetic.target_token"

        private const val CHANNEL_ID = "omn_phase0_controlled_source"
        private const val NOTIFICATION_TAG = MARKER
        private const val NOTIFICATION_ID = 0x4F4D4E
        private const val REQUEST_CODE = 0x504830
        private const val NO_ACTION_TOKEN = "OMN_SYNTHETIC_NO_ACTION"
    }
}
