package io.github.prince_dulb.ohmynotification.data

import io.github.prince_dulb.ohmynotification.core.model.ActionCapabilitySet
import io.github.prince_dulb.ohmynotification.core.model.NotificationIdentity
import io.github.prince_dulb.ohmynotification.core.model.NotificationObservation
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import io.github.prince_dulb.ohmynotification.core.model.RawVisibleContent
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationItemMergerTest {
    @Test
    fun initialCaptureUsesFirstReceivedTimeRatherThanSourcePostTime() {
        val result = NotificationItemMerger.merge(
            previous = null,
            observation = observation(observedAt = 2_000L, postTime = 1_000L),
            content = content(originalPostTime = 1_000L, title = "first"),
        )

        assertTrue(result.isNewItem)
        assertEquals(2_000L, result.item.firstReceivedAtEpochMillis)
        assertEquals(2_000L, result.item.sortTimeEpochMillis)
        assertEquals(1_000L, result.item.originalPostTimeEpochMillis)
    }

    @Test
    fun updatePreservesItemGenerationAndStableSortPosition() {
        val original = NotificationItemMerger.merge(
            previous = null,
            observation = observation(observedAt = 2_000L, postTime = 1_000L),
            content = content(originalPostTime = 1_000L, title = "first"),
        ).item.copy(itemId = 42L)

        val result = NotificationItemMerger.merge(
            previous = original,
            observation = observation(observedAt = 8_000L, postTime = 7_000L),
            content = content(originalPostTime = 7_000L, title = "updated"),
        )

        assertFalse(result.isNewItem)
        assertEquals(42L, result.item.itemId)
        assertEquals(0, result.item.lifecycleGeneration)
        assertEquals(2_000L, result.item.firstReceivedAtEpochMillis)
        assertEquals(2_000L, result.item.sortTimeEpochMillis)
        assertEquals(8_000L, result.item.lastUpdatedAtEpochMillis)
        assertEquals(1_000L, result.item.originalPostTimeEpochMillis)
        assertEquals("updated", result.item.title)
    }

    @Test
    fun postAfterRemovalStartsNewLifecycleGeneration() {
        val removed = NotificationItemMerger.merge(
            previous = null,
            observation = observation(observedAt = 2_000L, postTime = 1_000L),
            content = content(originalPostTime = 1_000L, title = "first"),
        ).item.copy(
            itemId = 42L,
            isRemoved = true,
            removedAtEpochMillis = 3_000L,
        )

        val result = NotificationItemMerger.merge(
            previous = removed,
            observation = observation(observedAt = 9_000L, postTime = 8_000L),
            content = content(originalPostTime = 8_000L, title = "reposted"),
        )

        assertTrue(result.isNewItem)
        assertEquals(0L, result.item.itemId)
        assertEquals(1, result.item.lifecycleGeneration)
        assertEquals(9_000L, result.item.sortTimeEpochMillis)
        assertFalse(result.item.isRemoved)
    }

    @Test
    fun duplicateCaptureStateIgnoresCallbackBookkeepingOnly() {
        val original = NotificationItemMerger.merge(
            previous = null,
            observation = observation(observedAt = 2_000L, postTime = 1_000L),
            content = content(originalPostTime = 1_000L, title = "same"),
        ).item.copy(itemId = 42L)
        val duplicate = NotificationItemMerger.merge(
            previous = original,
            observation = observation(observedAt = 3_000L, postTime = 1_000L),
            content = content(originalPostTime = 1_000L, title = "same"),
        ).item

        assertTrue(NotificationItemMerger.hasSameCapturedState(original, duplicate))
        assertEquals(2_000L, original.lastUpdatedAtEpochMillis)
        assertEquals(3_000L, duplicate.lastUpdatedAtEpochMillis)
    }

    @Test
    fun duplicateCaptureStateDetectsVisibleContentChange() {
        val original = NotificationItemMerger.merge(
            previous = null,
            observation = observation(observedAt = 2_000L, postTime = 1_000L),
            content = content(originalPostTime = 1_000L, title = "before"),
        ).item.copy(itemId = 42L)
        val updated = NotificationItemMerger.merge(
            previous = original,
            observation = observation(observedAt = 3_000L, postTime = 1_000L),
            content = content(originalPostTime = 1_000L, title = "after"),
        ).item

        assertFalse(NotificationItemMerger.hasSameCapturedState(original, updated))
    }

    private fun observation(observedAt: Long, postTime: Long) = NotificationObservation(
        runtimeSessionId = "runtime",
        listenerConnectionId = "connection",
        callbackKind = ObservedCallbackKind.POST_OR_UPDATE,
        identity = NotificationIdentity("example.app", "UserHandle{0}", "key", 7, null),
        postTimeEpochMillis = postTime,
        observedAtEpochMillis = observedAt,
        observedElapsedNanos = observedAt * 1_000_000L,
        content = RawVisibleContent(null, null, null, null, null, null, null, null, null),
        copyWarnings = emptySet(),
        actionCapabilities = ActionCapabilitySet(true, "example.app", 0, false),
        policyRevision = 0L,
        removalReason = null,
    )

    private fun content(originalPostTime: Long, title: String) = NormalizedContent(
        title = title,
        body = null,
        sourceLabelSnapshot = "Example",
        originalPostTimeEpochMillis = originalPostTime,
        contentFingerprint = "fingerprint-$title",
        warnings = emptySet(),
        strategyVersion = 1,
        titleOriginalCodePoints = title.length,
        bodyOriginalCodePoints = 0,
    )
}
