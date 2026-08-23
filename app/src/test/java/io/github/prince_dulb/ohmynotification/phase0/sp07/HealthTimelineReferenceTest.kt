package io.github.prince_dulb.ohmynotification.phase0.sp07

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTimelineReferenceTest {
    @Test
    fun currentConnectionProjectsBlueOnlyFromLastPositiveEvidenceToObservedNow() {
        val result = derive(
            evidence = listOf(positive("connect", 10, EvidenceKind.LISTENER_CONNECTED)),
            start = 0,
            end = 30,
            now = 25,
            currentSession = SESSION_A,
            currentConnection = CONNECTION_A,
            currentState = ListenerState.CONNECTED,
        )

        assertEquals(listOf("Y:0-10", "L:10-25", "Y:25-30"), result.snapshot())
        assertEquals(10L, result.lastPositiveEvidenceAtMillis)
        assertTrue(result.hasLiveProjection)
    }

    @Test
    fun adjacentPositiveEvidenceConfirmsOnlyTheIntervalBetweenThem() {
        val result = derive(
            evidence = listOf(
                positive("connect", 10, EvidenceKind.LISTENER_CONNECTED),
                positive("callback", 15, EvidenceKind.NOTIFICATION_CALLBACK),
            ),
            start = 5,
            end = 20,
        )

        assertEquals(listOf("Y:5-10", "B:10-15", "Y:15-20"), result.snapshot())
        assertFalse(result.hasLiveProjection)
    }

    @Test
    fun explicitDisconnectConfirmsUntilBoundaryAndNeverCreatesStoppedState() {
        val result = derive(
            evidence = listOf(
                positive("connect", 10, EvidenceKind.LISTENER_CONNECTED),
                boundary("disconnect", 17, EvidenceKind.LISTENER_DISCONNECTED),
            ),
            start = 5,
            end = 25,
        )

        assertEquals(listOf("Y:5-10", "D:10-17", "Y:17-25"), result.snapshot())
        assertEquals(setOf(IntervalState.CONFIRMED_RUNNING, IntervalState.UNCONFIRMED), result.intervals.map { it.state }.toSet())
    }

    @Test
    fun oldSessionWithoutBoundaryCannotProjectAcrossSuddenTermination() {
        val result = derive(
            evidence = listOf(
                positive("old-connect", 10, EvidenceKind.LISTENER_CONNECTED),
                evidence("new-process", 60, EvidenceKind.PROCESS_STARTED, SESSION_B, null),
            ),
            start = 10,
            end = 60,
            now = 60,
            currentSession = SESSION_B,
        )

        assertEquals(listOf("Y:10-60"), result.snapshot())
    }

    @Test
    fun processAndAccessFactsAloneNeverCreateBlueIntervals() {
        val result = derive(
            evidence = listOf(
                evidence("process", 5, EvidenceKind.PROCESS_STARTED, SESSION_A, null),
                evidence("access", 6, EvidenceKind.ACCESS_GRANTED, SESSION_A, null),
                evidence("status-permission", 7, EvidenceKind.STATUS_PERMISSION_DENIED, SESSION_A, null),
                evidence("channel", 8, EvidenceKind.STATUS_CHANNEL_DISABLED, SESSION_A, null),
            ),
            start = 0,
            end = 20,
        )

        assertEquals(listOf("Y:0-20"), result.snapshot())
        assertNull(result.lastPositiveEvidenceAtMillis)
        assertEquals(5, result.facets.size)
        assertEquals("CURRENT_SESSION_ACTIVE", result.facets.getValue(HealthFacet.PROCESS).state)
        assertEquals("GRANTED", result.facets.getValue(HealthFacet.LISTENER_ACCESS).state)
        assertEquals("DENIED", result.facets.getValue(HealthFacet.STATUS_PERMISSION).state)
        assertEquals("DISABLED", result.facets.getValue(HealthFacet.STATUS_CHANNEL).state)
        assertEquals("NEVER_OBSERVED", result.facets.getValue(HealthFacet.LISTENER).state)
    }

    @Test
    fun statusPermissionAndChannelDoNotShortenValidListenerEvidence() {
        val result = derive(
            evidence = listOf(
                positive("connect", 10, EvidenceKind.LISTENER_CONNECTED),
                evidence("denied", 12, EvidenceKind.STATUS_PERMISSION_DENIED, SESSION_A, null),
                evidence("disabled", 13, EvidenceKind.STATUS_CHANNEL_DISABLED, SESSION_A, null),
                positive("callback", 15, EvidenceKind.NOTIFICATION_CALLBACK),
            ),
            start = 5,
            end = 20,
        )

        assertEquals(listOf("Y:5-10", "B:10-15", "Y:15-20"), result.snapshot())
        assertEquals("DENIED", result.facets.getValue(HealthFacet.STATUS_PERMISSION).state)
        assertEquals("DISABLED", result.facets.getValue(HealthFacet.STATUS_CHANNEL).state)
        assertEquals("CONNECTED", result.facets.getValue(HealthFacet.LISTENER).state)
    }

    @Test
    fun listenerAccessRevocationEndsAllCurrentBlueCandidates() {
        val result = derive(
            evidence = listOf(
                positive("connect", 10, EvidenceKind.LISTENER_CONNECTED),
                evidence("revoked", 14, EvidenceKind.ACCESS_NOT_GRANTED, SESSION_A, null),
                positive("callback-after-revoke", 18, EvidenceKind.NOTIFICATION_CALLBACK),
            ),
            start = 5,
            end = 25,
        )

        assertEquals(listOf("Y:5-10", "B:10-14", "Y:14-25"), result.snapshot())
    }

    @Test
    fun equalUtcTimeUsesEvidenceSequenceForDeterministicZeroLengthBoundary() {
        val result = HealthTimelineReference.derive(
            evidence = listOf(
                positive("connect", 10, EvidenceKind.LISTENER_CONNECTED, sequence = 1),
                boundary("disconnect", 10, EvidenceKind.LISTENER_DISCONNECTED, sequence = 2),
            ),
            query = query(0, 20),
        )

        assertEquals(listOf("Y:0-20"), result.snapshot())
    }

    @Test
    fun crossSessionConnectionReuseIsRejectedConservatively() {
        val result = derive(
            evidence = listOf(
                positive("a", 10, EvidenceKind.LISTENER_CONNECTED),
                positive("b", 15, EvidenceKind.LISTENER_CONNECTED, session = SESSION_B),
            ),
            start = 0,
            end = 20,
        )

        assertEquals(listOf("Y:0-20"), result.snapshot())
        assertTrue(DerivationDiagnostic.CONNECTION_REUSED_ACROSS_SESSIONS in result.diagnostics)
    }

    @Test
    fun wallClockRollbackProducesDiagnosticAndConservativelyRejectsBlueIntervals() {
        val result = derive(
            evidence = listOf(
                positive("connect", 20, EvidenceKind.LISTENER_CONNECTED, sequence = 1, elapsed = 100),
                positive("callback", 10, EvidenceKind.NOTIFICATION_CALLBACK, sequence = 2, elapsed = 200),
            ),
            start = 0,
            end = 30,
        )

        assertTrue(DerivationDiagnostic.NON_MONOTONIC_EVIDENCE in result.diagnostics)
        assertEquals(listOf("Y:0-30"), result.snapshot())
        assertCompleteCoverage(result, 0, 30)
    }

    @Test
    fun randomValidEvidenceAlwaysProducesCompleteNonOverlappingCoverage() {
        val random = Random(2026082307)
        repeat(1_000) { iteration ->
            val first = random.nextLong(0, 1_000)
            val second = random.nextLong(first, 1_001)
            val evidence = buildList {
                add(positive("c-$iteration", first, EvidenceKind.LISTENER_CONNECTED))
                if (second > first) add(positive("p-$iteration", second, EvidenceKind.NOTIFICATION_CALLBACK))
            }
            val result = derive(evidence, start = 0, end = 1_100)
            assertCompleteCoverage(result, 0, 1_100)
        }
    }

    private fun assertCompleteCoverage(result: HealthTimelineResult, start: Long, end: Long) {
        assertTrue(result.intervals.isNotEmpty())
        assertEquals(start, result.intervals.first().startMillis)
        assertEquals(end, result.intervals.last().endMillis)
        result.intervals.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endMillis, right.startMillis)
        }
        assertTrue(result.intervals.all { interval -> interval.endMillis > interval.startMillis })
    }

    private fun derive(
        evidence: List<HealthEvidence>,
        start: Long,
        end: Long,
        now: Long = end,
        currentSession: String? = null,
        currentConnection: String? = null,
        currentState: ListenerState = ListenerState.NEVER_OBSERVED,
    ): HealthTimelineResult = HealthTimelineReference.derive(
        evidence,
        HealthQuery(
            rangeStartMillis = start,
            rangeEndMillis = end,
            observedNowMillis = now,
            currentRuntimeSessionId = currentSession,
            currentConnectionId = currentConnection,
            currentListenerState = currentState,
        ),
    )

    private fun query(start: Long, end: Long): HealthQuery = HealthQuery(
        rangeStartMillis = start,
        rangeEndMillis = end,
        observedNowMillis = end,
    )

    private fun positive(
        id: String,
        at: Long,
        kind: EvidenceKind,
        sequence: Long = at,
        elapsed: Long? = at,
        session: String = SESSION_A,
    ): HealthEvidence = evidence(id, at, kind, session, CONNECTION_A, sequence, elapsed)

    private fun boundary(
        id: String,
        at: Long,
        kind: EvidenceKind,
        sequence: Long = at,
    ): HealthEvidence = evidence(id, at, kind, SESSION_A, CONNECTION_A, sequence)

    private fun evidence(
        id: String,
        at: Long,
        kind: EvidenceKind,
        session: String,
        connection: String?,
        sequence: Long = at,
        elapsed: Long? = at,
    ): HealthEvidence = HealthEvidence(
        evidenceId = id,
        evidenceSequence = sequence,
        runtimeSessionId = session,
        connectionId = connection,
        kind = kind,
        occurredAtMillis = at,
        occurredElapsedMillis = elapsed,
    )

    private fun HealthTimelineResult.snapshot(): List<String> = intervals.map { interval ->
        val prefix = when {
            interval.state == IntervalState.UNCONFIRMED -> "Y"
            interval.isLiveProjection -> "L"
            interval.explanation == IntervalExplanation.UNTIL_EXPLICIT_DISCONNECT -> "D"
            else -> "B"
        }
        "$prefix:${interval.startMillis}-${interval.endMillis}"
    }

    private companion object {
        const val SESSION_A = "session-a"
        const val SESSION_B = "session-b"
        const val CONNECTION_A = "connection-a"
    }
}
