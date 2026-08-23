package io.github.prince_dulb.ohmynotification.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositiveHealthEvidenceGateTest {
    @Test
    fun repeatedUpdatesAreDueAtMostOncePerWindow() {
        val gate = PositiveHealthEvidenceGate()
        val first = evidence(connection = "one", occurredAt = 1_000L)

        assertTrue(gate.isDue(first, minimumIntervalMillis = 300_000L))
        gate.markRecorded(first)
        assertFalse(gate.isDue(first.copy(occurredAtEpochMillis = 300_999L), 300_000L))
        assertTrue(gate.isDue(first.copy(occurredAtEpochMillis = 301_000L), 300_000L))
    }

    @Test
    fun connectionsAreIndependentAndClockRollbackDoesNotSuppressForever() {
        val gate = PositiveHealthEvidenceGate()
        gate.markRecorded(evidence(connection = "one", occurredAt = 10_000L))

        assertTrue(gate.isDue(evidence(connection = "two", occurredAt = 10_001L), 300_000L))
        assertTrue(gate.isDue(evidence(connection = "one", occurredAt = 9_999L), 300_000L))
    }

    @Test
    fun rememberedConnectionsAreBounded() {
        val gate = PositiveHealthEvidenceGate(maxRememberedConnections = 2)
        gate.markRecorded(evidence(connection = "one", occurredAt = 1L))
        gate.markRecorded(evidence(connection = "two", occurredAt = 2L))
        gate.markRecorded(evidence(connection = "three", occurredAt = 3L))

        assertTrue(gate.isDue(evidence(connection = "one", occurredAt = 4L), 300_000L))
        assertFalse(gate.isDue(evidence(connection = "three", occurredAt = 4L), 300_000L))
    }

    private fun evidence(connection: String, occurredAt: Long) = PositiveHealthEvidence(
        runtimeSessionId = "session",
        listenerConnectionId = connection,
        occurredAtEpochMillis = occurredAt,
    )
}
