package io.github.prince_dulb.ohmynotification.core.model

enum class NotificationType {
    MEDIA,
    COMMUNICATION,
    ALERT_OR_NAVIGATION,
    ONGOING_OR_PROGRESS,
    OTHER,
}

data class NotificationTypeSignals(
    val category: String?,
    val hasMediaSession: Boolean,
    val isOngoing: Boolean,
)

object NotificationTypeClassifier {
    fun classify(signals: NotificationTypeSignals): NotificationType = when {
        signals.hasMediaSession || signals.category == CATEGORY_TRANSPORT -> NotificationType.MEDIA
        signals.category in COMMUNICATION_CATEGORIES -> NotificationType.COMMUNICATION
        signals.category in ALERT_OR_NAVIGATION_CATEGORIES -> NotificationType.ALERT_OR_NAVIGATION
        signals.isOngoing || signals.category in ONGOING_OR_PROGRESS_CATEGORIES ->
            NotificationType.ONGOING_OR_PROGRESS
        else -> NotificationType.OTHER
    }

    private const val CATEGORY_TRANSPORT = "transport"
    private val COMMUNICATION_CATEGORIES = setOf("call", "msg", "email", "social", "missed_call", "voicemail")
    private val ALERT_OR_NAVIGATION_CATEGORIES = setOf(
        "alarm",
        "reminder",
        "event",
        "navigation",
        "location_sharing",
        "workout",
        "stopwatch",
    )
    private val ONGOING_OR_PROGRESS_CATEGORIES = setOf("service", "progress", "status")
}

data class AppSettingsSnapshot(
    val recordedNotificationTypes: Set<NotificationType>,
    val groupingWindowMinutes: Int,
) {
    companion object {
        val DEFAULT = AppSettingsSnapshot(
            recordedNotificationTypes = NotificationType.entries.toSet() - NotificationType.MEDIA,
            groupingWindowMinutes = 15,
        )
    }
}

object GroupingWindowOptions {
    val minutes = listOf(5, 15, 30, 60, 120, 360, 720, 1_440)

    fun normalize(value: Int): Int = minutes.minBy { candidate -> kotlin.math.abs(candidate - value) }
}
