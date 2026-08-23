package io.github.prince_dulb.ohmynotification.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerRebindGateTest {
    @Test
    fun automaticRequestsAreCooledDownAndBounded() {
        val gate = ListenerRebindGate(
            automaticCooldownMillis = 10L,
            userCooldownMillis = 1L,
            maximumAutomaticRequests = 2,
        )

        assertTrue(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 100L))
        assertFalse(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 109L))
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 110L))
        assertFalse(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 120L))
    }

    @Test
    fun userRequestBypassesAutomaticCooldownAndLimit() {
        val gate = ListenerRebindGate(
            automaticCooldownMillis = 10L,
            userCooldownMillis = 5L,
            maximumAutomaticRequests = 1,
        )
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 100L))

        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 101L))
        assertFalse(gate.tryAcquire(ListenerRebindTrigger.USER, 105L))
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 106L))
        assertFalse(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 116L))
    }

    @Test
    fun automaticRequestWaitsAfterUserRequest() {
        val gate = ListenerRebindGate(
            automaticCooldownMillis = 10L,
            userCooldownMillis = 1L,
            maximumAutomaticRequests = 2,
        )

        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 100L))
        assertFalse(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 109L))
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 110L))
    }

    @Test
    fun elapsedClockResetDoesNotCreatePermanentCooldown() {
        val gate = ListenerRebindGate(
            automaticCooldownMillis = 10L,
            userCooldownMillis = 10L,
            maximumAutomaticRequests = 1,
        )
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 100L))

        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 5L))
    }
}
