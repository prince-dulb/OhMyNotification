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
import io.github.prince_dulb.ohmynotification.core.health.StatusPresentationDeriver
import io.github.prince_dulb.ohmynotification.core.health.StatusPresentationState
import io.github.prince_dulb.ohmynotification.data.NotificationCommit
import io.github.prince_dulb.ohmynotification.data.NotificationItemEntity

class StatusNotificationController(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var connectionObserved = false
    private var recordCount = 0L
    private var latestSource: String? = null
    private var latestTitle: String? = null
    private var latestEventTimeEpochMillis: Long? = null
    private var lastPublishedSignature: StatusSignature? = null
    private var publishedUpdateCount = 0L
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
    fun onListenerConnectionChanged(
        isConnected: Boolean,
        currentRecordCount: Long? = null,
        latestItem: NotificationItemEntity? = null,
    ) {
        connectionObserved = true
        connected = isConnected
        updateSnapshot(currentRecordCount, latestItem)
        schedule(immediate = true)
    }

    @Synchronized
    fun onExternalStateChanged(
        currentRecordCount: Long? = null,
        latestItem: NotificationItemEntity? = null,
    ) {
        updateSnapshot(currentRecordCount, latestItem)
        schedule(immediate = true)
    }

    private fun updateSnapshot(currentRecordCount: Long?, latestItem: NotificationItemEntity?) {
        if (currentRecordCount != null) recordCount = currentRecordCount
        if (latestItem != null) {
            latestSource = latestItem.sourceLabelSnapshot ?: latestItem.sourcePackage
            latestTitle = latestItem.title
            latestEventTimeEpochMillis = latestItem.firstReceivedAtEpochMillis
        }
    }

    fun isChannelEnabled(): Boolean =
        notificationManager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun channelId(): String = CHANNEL_ID

    fun isStatusNotificationActive(): Boolean = notificationManager.activeNotifications.any { item ->
        item.id == NOTIFICATION_ID && item.notification.channelId == CHANNEL_ID
    }

    @Synchronized
    fun publishedUpdateCount(): Long = publishedUpdateCount

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
            lastPublishedSignature = null
            return
        }
        if (!isChannelEnabled()) {
            lastPublishedSignature = null
            return
        }
        val listenerAccessGranted = notificationManager.isNotificationListenerAccessGranted(
            OmnNotificationListenerComponent.componentName(context),
        )
        val presentation = StatusPresentationDeriver.derive(
            listenerAccessGranted = listenerAccessGranted,
            connectionObserved = connectionObserved,
            listenerConnected = connected,
        )
        val source = latestSource
        val privateTitle = when (presentation) {
            StatusPresentationState.LISTENING -> context.getString(R.string.status_title_connected)
            StatusPresentationState.WAITING_FOR_CONNECTION ->
                context.getString(R.string.status_title_waiting_for_connection)
            StatusPresentationState.LISTENER_INTERRUPTED ->
                context.getString(R.string.status_title_disconnected)
            StatusPresentationState.ACCESS_REQUIRED -> context.getString(R.string.status_title_access_required)
        }
        val summaryText = when (presentation) {
            StatusPresentationState.LISTENING -> context.getString(R.string.status_connected, recordCount)
            StatusPresentationState.WAITING_FOR_CONNECTION -> context.getString(R.string.status_waiting_for_connection)
            StatusPresentationState.LISTENER_INTERRUPTED ->
                context.getString(R.string.status_disconnected, recordCount)
            StatusPresentationState.ACCESS_REQUIRED -> context.getString(R.string.status_access_required)
        }
        val privateDetail = if (presentation == StatusPresentationState.LISTENING) {
            listOfNotNull(summaryText, source, latestTitle)
                .joinToString(context.getString(R.string.status_detail_separator))
        } else {
            summaryText
        }
        val publicDetail = if (presentation == StatusPresentationState.LISTENING && source != null) {
            context.getString(R.string.status_public_recent_source, source)
        } else {
            summaryText
        }
        val signature = StatusSignature(
            presentation = presentation,
            recordCount = recordCount,
            latestSource = source,
            latestTitle = latestTitle,
            latestEventTimeEpochMillis = latestEventTimeEpochMillis,
        )
        if (signature == lastPublishedSignature) return
        val publicVersion = baseBuilder()
            .setContentTitle(privateTitle)
            .setContentText(publicDetail)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        val notification = baseBuilder()
            .setContentTitle(privateTitle)
            .setContentText(privateDetail)
            .setStyle(Notification.BigTextStyle().bigText(privateDetail))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
            .onSuccess {
                lastPublishedSignature = signature
                publishedUpdateCount += 1L
            }
    }

    private fun baseBuilder(): Notification.Builder = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_status_notification)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_STATUS)
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

    private data class StatusSignature(
        val presentation: StatusPresentationState,
        val recordCount: Long,
        val latestSource: String?,
        val latestTitle: String?,
        val latestEventTimeEpochMillis: Long?,
    )
}
