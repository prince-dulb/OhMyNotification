package io.github.prince_dulb.ohmynotification.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerRebindGateTest {
    @Test
    fun automaticRequestsAreCooledDownAndBounded() {
        val gate = ListenerRebindGate(cooldownMillis = 10L, maximumAutomaticRequests = 2)

        assertTrue(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 100L))
        assertFalse(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 109L))
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 110L))
        assertFalse(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 120L))
    }

    @Test
    fun userRequestRemainsAvailableAfterAutomaticLimit() {
        val gate = ListenerRebindGate(cooldownMillis = 10L, maximumAutomaticRequests = 1)
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 100L))

        assertFalse(gate.tryAcquire(ListenerRebindTrigger.AUTOMATIC, 110L))
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 110L))
        assertFalse(gate.tryAcquire(ListenerRebindTrigger.USER, 119L))
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 120L))
    }

    @Test
    fun elapsedClockResetDoesNotCreatePermanentCooldown() {
        val gate = ListenerRebindGate(cooldownMillis = 10L, maximumAutomaticRequests = 1)
        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 100L))

        assertTrue(gate.tryAcquire(ListenerRebindTrigger.USER, 5L))
    }
}
