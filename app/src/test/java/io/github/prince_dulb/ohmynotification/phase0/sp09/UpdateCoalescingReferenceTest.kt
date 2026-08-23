package io.github.prince_dulb.ohmynotification.phase0.sp09

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCoalescingReferenceTest {
    private val reference = UpdateCoalescingReference()

    @Test
    fun firstObservationPersistsImmediately() {
        val decision = reference.onContent(previous = null, observedAtNanos = seconds(0))

        assertEquals(UpdateCoalescingAction.PERSIST_CURRENT, decision.action)
        assertEquals(seconds(0), decision.state?.lastPersistedAtNanos)
        assertNull(decision.scheduleAfterNanos)
    }

    @Test
    fun sixSecondUpdatesStayPendingUntilTenSecondsQuiet() {
        var state = requireNotNull(reference.onContent(null, seconds(0)).state)
        val firstUpdate = reference.onContent(state, seconds(6))
        state = requireNotNull(firstUpdate.state)
        assertEquals(UpdateCoalescingAction.HOLD_LATEST, firstUpdate.action)
        assertEquals(seconds(10), firstUpdate.scheduleAfterNanos)

        val secondUpdate = reference.onContent(state, seconds(12))
        state = requireNotNull(secondUpdate.state)
        assertNull(secondUpdate.scheduleAfterNanos)

        val firstTimer = reference.onTimer(state, seconds(16))
        assertEquals(UpdateCoalescingAction.HOLD_LATEST, firstTimer.action)
        assertEquals(seconds(6), firstTimer.scheduleAfterNanos)

        val thirdUpdate = reference.onContent(requireNotNull(firstTimer.state), seconds(18))
        val secondTimer = reference.onTimer(requireNotNull(thirdUpdate.state), seconds(22))
        assertEquals(UpdateCoalescingAction.HOLD_LATEST, secondTimer.action)
        assertEquals(seconds(6), secondTimer.scheduleAfterNanos)

        val flush = reference.onTimer(requireNotNull(secondTimer.state), seconds(28))
        assertEquals(UpdateCoalescingAction.FLUSH_PENDING, flush.action)
        assertTrue(requireNotNull(flush.state).hasPendingUpdate.not())
    }

    @Test
    fun continuousUpdatesFlushAtMaximumPendingAge() {
        var state = requireNotNull(reference.onContent(null, seconds(0)).state)
        state = requireNotNull(reference.onContent(state, seconds(6)).state)
        for (second in 12..60 step 6) {
            state = requireNotNull(reference.onContent(state, seconds(second)).state)
        }

        val decision = reference.onTimer(state, seconds(66))

        assertEquals(UpdateCoalescingAction.FLUSH_PENDING, decision.action)
        assertEquals(seconds(66), decision.state?.lastPersistedAtNanos)
    }

    @Test
    fun removalFlushesLatestPendingUpdateBeforeRemoval() {
        val persisted = requireNotNull(reference.onContent(null, seconds(0)).state)
        val pending = requireNotNull(reference.onContent(persisted, seconds(6)).state)

        val decision = reference.onRemoval(pending)

        assertEquals(UpdateCoalescingAction.FLUSH_PENDING_THEN_REMOVE, decision.action)
        assertNull(decision.state)
    }

    @Test
    fun removalWithoutPendingUpdateDoesNotInventAFlush() {
        val persisted = requireNotNull(reference.onContent(null, seconds(0)).state)

        val decision = reference.onRemoval(persisted)

        assertEquals(UpdateCoalescingAction.REMOVE_CURRENT, decision.action)
        assertNull(decision.state)
    }

    private fun seconds(value: Int): Long = value * 1_000_000_000L
}
