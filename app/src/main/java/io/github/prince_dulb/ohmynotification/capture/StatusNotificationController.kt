package io.github.prince_dulb.ohmynotification.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.prince_dulb.ohmynotification.MainActivity
import io.github.prince_dulb.ohmynotification.R
import io.github.prince_dulb.ohmynotification.data.NotificationCommit

class StatusNotificationController(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var recordCount = 0L
    private var latestSource: String? = null
    private var latestTitle: String? = null
    private var latestEventTimeEpochMillis: Long? = null
    private val publishRunnable = Runnable(::publishNow)

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.status_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.status_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    @Synchronized
    fun onConnectionChanged(isConnected: Boolean, currentRecordCount: Long? = null) {
        connected = isConnected
        if (currentRecordCount != null) recordCount = currentRecordCount
        schedule(immediate = true)
    }

    fun isChannelEnabled(): Boolean =
        notificationManager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun channelId(): String = CHANNEL_ID

    @Synchronized
    fun onCommitted(commit: NotificationCommit) {
        if (!commit.updatesStatusSummary) return
        if (commit.isNewItem) recordCount += 1
        latestSource = commit.item.sourceLabelSnapshot ?: commit.item.sourcePackage
        latestTitle = commit.item.title
        latestEventTimeEpochMillis = commit.item.firstReceivedAtEpochMillis
        schedule(immediate = false)
    }

    @Synchronized
    private fun schedule(immediate: Boolean) {
        handler.removeCallbacks(publishRunnable)
        handler.postDelayed(publishRunnable, if (immediate) 0L else UPDATE_DEBOUNCE_MILLIS)
    }

    @Synchronized
    private fun publishNow() {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val stateText = if (connected) {
            context.getString(R.string.status_connected, recordCount)
        } else {
            context.getString(R.string.status_disconnected, recordCount)
        }
        val source = latestSource
        val privateDetail = listOfNotNull(source, latestTitle)
            .joinToString(context.getString(R.string.status_detail_separator))
            .ifBlank { context.getString(R.string.status_waiting) }
        val publicDetail = source ?: context.getString(R.string.status_waiting)
        val publicVersion = baseBuilder()
            .setContentTitle(stateText)
            .setContentText(publicDetail)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        val notification = baseBuilder()
            .setContentTitle(stateText)
            .setContentText(privateDetail)
            .setStyle(Notification.BigTextStyle().bigText(privateDetail))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun baseBuilder(): Notification.Builder = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_status_notification)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .apply {
            latestEventTimeEpochMillis?.let { eventTime ->
                setWhen(eventTime)
                setShowWhen(true)
            }
        }
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

    private companion object {
        const val CHANNEL_ID = "running_status"
        const val NOTIFICATION_ID = 0x0D4E
        const val UPDATE_DEBOUNCE_MILLIS = 750L
    }
}
