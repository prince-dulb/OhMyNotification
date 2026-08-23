package io.github.prince_dulb.ohmynotification.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTimelineTest {
    @Test
    fun currentConnectionProjectsBlueToQueryEnd() {
        val intervals = HealthTimelineDeriver.derive(
            facts = listOf(fact(1, "LISTENER_CONNECTED", 10, "session", "connection")),
            query = query(5, 20, "session", "connection"),
        )

        assertEquals(HealthIntervalState.UNCONFIRMED, intervals[0].state)
        assertEquals(5_000L, intervals[0].startEpochMillis)
        assertEquals(10_000L, intervals[0].endEpochMillis)
        assertEquals(HealthIntervalState.CONFIRMED_RUNNING, intervals[1].state)
        assertTrue(intervals[1].isLiveProjection)
        assertEquals(20_000L, intervals[1].endEpochMillis)
    }

    @Test
    fun explicitDisconnectEndsConfirmedInterval() {
        val intervals = HealthTimelineDeriver.derive(
            facts = listOf(
                fact(1, "LISTENER_CONNECTED", 10, "session", "connection"),
                fact(2, "LISTENER_DISCONNECTED", 17, "session", "connection"),
            ),
            query = query(5, 20, null, null),
        )

        assertTrue(HealthTimelineDeriver.isFullyConfirmed(intervals, 10_000L, 17_000L))
        assertFalse(HealthTimelineDeriver.isFullyConfirmed(intervals, 10_000L, 18_000L))
    }

    @Test
    fun oldSessionDoesNotProjectAcrossProcessGap() {
        val intervals = HealthTimelineDeriver.derive(
            facts = listOf(
                fact(1, "LISTENER_CONNECTED", 10, "old", "old-connection"),
                fact(2, "PROCESS_STARTED", 20, "new", null),
            ),
            query = query(10, 30, "new", null),
        )

        assertEquals(1, intervals.size)
        assertEquals(HealthIntervalState.UNCONFIRMED, intervals.single().state)
    }

    @Test
    fun positiveEvidencePairConfirmsOnlyBetweenEvidence() {
        val intervals = HealthTimelineDeriver.derive(
            facts = listOf(
                fact(1, "LISTENER_CONNECTED", 10, "session", "connection"),
                fact(2, "NOTIFICATION_COMMITTED", 15, "session", "connection"),
            ),
            query = query(5, 20, null, null),
        )

        assertTrue(HealthTimelineDeriver.isFullyConfirmed(intervals, 10_000L, 15_000L))
        assertFalse(HealthTimelineDeriver.isFullyConfirmed(intervals, 15_000L, 20_000L))
    }

    private fun fact(
        id: Long,
        kind: String,
        second: Long,
        session: String,
        connection: String?,
    ) = HealthFact(id, kind, second * 1_000L, session, connection)

    private fun query(start: Long, end: Long, session: String?, connection: String?) = HealthTimelineQuery(
        rangeStartEpochMillis = start * 1_000L,
        rangeEndEpochMillis = end * 1_000L,
        currentRuntimeSessionId = session,
        currentListenerConnectionId = connection,
    )
}
