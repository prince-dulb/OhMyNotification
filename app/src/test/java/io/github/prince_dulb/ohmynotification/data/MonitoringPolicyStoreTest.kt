package io.github.prince_dulb.ohmynotification.data

import io.github.prince_dulb.ohmynotification.core.model.NotificationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringPolicyStoreTest {
    @Test
    fun keepsAutomaticExclusionAndPublishesOnlyRealUserChanges() {
        val store = MonitoringPolicyStore("example.self")

        assertTrue(store.snapshot().excludes("example.self"))
        assertEquals(0L, store.snapshot().revision)

        store.replaceUserExclusions(setOf("example.alpha"))
        assertTrue(store.snapshot().excludes("example.alpha"))
        assertEquals(1L, store.snapshot().revision)

        store.replaceUserExclusions(setOf("example.alpha"))
        assertEquals(1L, store.snapshot().revision)

        store.setExcluded("example.beta", true)
        assertEquals(setOf("example.alpha", "example.beta"), store.snapshot().userExcludedPackages)
        assertEquals(2L, store.snapshot().revision)

        store.setExcluded("example.alpha", false)
        assertFalse(store.snapshot().excludes("example.alpha"))
        assertTrue(store.snapshot().excludes("example.self"))
        assertEquals(3L, store.snapshot().revision)

        store.replaceRecordedNotificationTypes(setOf(NotificationType.OTHER))
        assertTrue(store.snapshot().records(NotificationType.OTHER))
        assertFalse(store.snapshot().records(NotificationType.MEDIA))
        assertEquals(4L, store.snapshot().revision)

        store.replaceRecordedNotificationTypes(setOf(NotificationType.OTHER))
        assertEquals(4L, store.snapshot().revision)
    }
}
