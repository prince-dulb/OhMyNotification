package io.github.prince_dulb.ohmynotification.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsTest {
    @Test
    fun mediaSignalsHavePriorityAndAreDisabledByDefault() {
        assertEquals(
            NotificationType.MEDIA,
            NotificationTypeClassifier.classify(
                NotificationTypeSignals(category = "msg", hasMediaSession = true, isOngoing = true),
            ),
        )
        assertFalse(NotificationType.MEDIA in AppSettingsSnapshot.DEFAULT.recordedNotificationTypes)
    }

    @Test
    fun knownCategoriesMapToStableUserFacingGroups() {
        assertEquals(NotificationType.MEDIA, classify("transport"))
        assertEquals(NotificationType.COMMUNICATION, classify("msg"))
        assertEquals(NotificationType.COMMUNICATION, classify("call"))
        assertEquals(NotificationType.ALERT_OR_NAVIGATION, classify("alarm"))
        assertEquals(NotificationType.ALERT_OR_NAVIGATION, classify("navigation"))
        assertEquals(NotificationType.ONGOING_OR_PROGRESS, classify("progress"))
        assertEquals(NotificationType.OTHER, classify("promo"))
        assertEquals(NotificationType.OTHER, classify(null))
    }

    @Test
    fun ongoingFlagClassifiesOtherwiseUnknownNotification() {
        assertEquals(
            NotificationType.ONGOING_OR_PROGRESS,
            NotificationTypeClassifier.classify(
                NotificationTypeSignals(category = null, hasMediaSession = false, isOngoing = true),
            ),
        )
    }

    @Test
    fun groupingWindowUsesDocumentedDiscreteOptions() {
        assertEquals(15, GroupingWindowOptions.normalize(15))
        assertEquals(30, GroupingWindowOptions.normalize(28))
        assertEquals(5, GroupingWindowOptions.normalize(-1))
        assertEquals(1_440, GroupingWindowOptions.normalize(5_000))
        assertTrue(GroupingWindowOptions.minutes.zipWithNext().all { (left, right) -> left < right })
    }

    private fun classify(category: String?): NotificationType = NotificationTypeClassifier.classify(
        NotificationTypeSignals(category = category, hasMediaSession = false, isOngoing = false),
    )
}
