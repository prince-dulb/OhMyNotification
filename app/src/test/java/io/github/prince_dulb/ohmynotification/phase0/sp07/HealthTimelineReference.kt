package io.github.prince_dulb.ohmynotification.phase0.sp07

internal enum class EvidenceKind {
    PROCESS_STARTED,
    NORMAL_STOP,
    LISTENER_CONNECTED,
    LISTENER_DISCONNECTED,
    NOTIFICATION_CALLBACK,
    RECOVERY_COMPLETED,
    ACCESS_GRANTED,
    ACCESS_NOT_GRANTED,
    ACCESS_UNKNOWN,
    STATUS_PERMISSION_GRANTED,
    STATUS_PERMISSION_DENIED,
    STATUS_CHANNEL_ENABLED,
    STATUS_CHANNEL_DISABLED,
}

internal data class HealthEvidence(
    val evidenceId: String,
    val evidenceSequence: Long,
    val runtimeSessionId: String,
    val connectionId: String?,
    val kind: EvidenceKind,
    val occurredAtMillis: Long,
    val occurredElapsedMillis: Long? = null,
) {
    init {
        require(evidenceId.isNotBlank())
        require(runtimeSessionId.isNotBlank())
        if (kind.isListenerConnectionEvidence()) require(!connectionId.isNullOrBlank())
    }
}

internal enum class ListenerState {
    NEVER_OBSERVED,
    CONNECTED,
    DISCONNECTED,
}

internal data class HealthQuery(
    val rangeStartMillis: Long,
    val rangeEndMillis: Long,
    val observedNowMillis: Long,
    val currentRuntimeSessionId: String? = null,
    val currentConnectionId: String? = null,
    val currentListenerState: ListenerState = ListenerState.NEVER_OBSERVED,
) {
    init {
        require(rangeEndMillis > rangeStartMillis)
    }
}

internal enum class IntervalState {
    CONFIRMED_RUNNING,
    UNCONFIRMED,
}

internal enum class IntervalExplanation {
    BETWEEN_POSITIVE_EVIDENCE,
    UNTIL_EXPLICIT_DISCONNECT,
    CURRENT_CONNECTED_PROJECTION,
    INSUFFICIENT_EVIDENCE,
}

internal data class HealthInterval(
    val startMillis: Long,
    val endMillis: Long,
    val state: IntervalState,
    val evidenceRefs: List<String>,
    val connectionId: String?,
    val isLiveProjection: Boolean,
    val explanation: IntervalExplanation,
) {
    init {
        require(endMillis > startMillis)
        require(state != IntervalState.CONFIRMED_RUNNING || connectionId != null)
        require(state != IntervalState.UNCONFIRMED || connectionId == null)
    }
}

internal enum class DerivationDiagnostic {
    INVALID_CONNECTION_REFERENCE,
    CONNECTION_REUSED_ACROSS_SESSIONS,
    NON_MONOTONIC_EVIDENCE,
    OVERLAPPING_CONNECTION_EVIDENCE,
}

internal enum class HealthFacet {
    PROCESS,
    LISTENER,
    LISTENER_ACCESS,
    STATUS_PERMISSION,
    STATUS_CHANNEL,
}

internal data class FacetSummary(
    val facet: HealthFacet,
    val state: String,
    val lastObservedAtMillis: Long?,
    val isCurrentSessionObservation: Boolean,
)

internal data class HealthTimelineResult(
    val intervals: List<HealthInterval>,
    val lastPositiveEvidenceAtMillis: Long?,
    val hasLiveProjection: Boolean,
    val facets: Map<HealthFacet, FacetSummary>,
    val diagnostics: Set<DerivationDiagnostic>,
)

internal object HealthTimelineReference {
    fun derive(evidence: List<HealthEvidence>, query: HealthQuery): HealthTimelineResult {
        val diagnostics = linkedSetOf<DerivationDiagnostic>()
        val validEvidence = validateConnectionOwnership(evidence, diagnostics)
        val nonMonotonicSessions = detectNonMonotonicEvidence(validEvidence, diagnostics)
        val ordered = validEvidence.sortedWith(
            compareBy(HealthEvidence::occurredAtMillis, HealthEvidence::evidenceSequence),
        )
        val timelineEvidence = ordered.filterNot { item -> item.runtimeSessionId in nonMonotonicSessions }
        val positive = timelineEvidence.filter { item -> item.kind.isPositiveListenerEvidence() }
        val candidates = arrayListOf<HealthInterval>()

        positive.groupBy { item -> ConnectionKey(item.runtimeSessionId, requireNotNull(item.connectionId)) }
            .forEach { (connection, facts) ->
                val connectionEvidence = facts.sortedWith(
                    compareBy(HealthEvidence::occurredAtMillis, HealthEvidence::evidenceSequence),
                )
                connectionEvidence.zipWithNext().forEach { (start, end) ->
                    val boundary = firstBoundaryBetween(timelineEvidence, connection, start, end.occurredAtMillis)
                    val intervalEnd = boundary?.occurredAtMillis ?: end.occurredAtMillis
                    addCandidate(
                        candidates,
                        query,
                        start,
                        boundary,
                        intervalEnd,
                        IntervalExplanation.BETWEEN_POSITIVE_EVIDENCE,
                        isLive = false,
                    )
                }

                val last = connectionEvidence.last()
                val boundary = firstBoundaryAfter(timelineEvidence, connection, last)
                if (boundary != null) {
                    addCandidate(
                        candidates,
                        query,
                        last,
                        boundary,
                        boundary.occurredAtMillis,
                        IntervalExplanation.UNTIL_EXPLICIT_DISCONNECT,
                        isLive = false,
                    )
                } else if (
                    query.currentListenerState == ListenerState.CONNECTED &&
                    query.currentRuntimeSessionId == connection.sessionId &&
                    query.currentConnectionId == connection.connectionId
                ) {
                    addCandidate(
                        candidates,
                        query,
                        last,
                        null,
                        minOf(query.observedNowMillis, query.rangeEndMillis),
                        IntervalExplanation.CURRENT_CONNECTED_PROJECTION,
                        isLive = true,
                    )
                }
            }

        val normalized = normalize(candidates, query, diagnostics)
        return HealthTimelineResult(
            intervals = normalized,
            lastPositiveEvidenceAtMillis = positive.maxOfOrNull(HealthEvidence::occurredAtMillis),
            hasLiveProjection = normalized.any(HealthInterval::isLiveProjection),
            facets = deriveFacets(ordered, query),
            diagnostics = diagnostics,
        )
    }

    private fun deriveFacets(
        ordered: List<HealthEvidence>,
        query: HealthQuery,
    ): Map<HealthFacet, FacetSummary> = HealthFacet.entries.associateWith { facet ->
        val latest = ordered.lastOrNull { item -> item.kind.facet() == facet }
        val memoryListenerState = if (facet == HealthFacet.LISTENER) {
            when (query.currentListenerState) {
                ListenerState.CONNECTED -> "CONNECTED"
                ListenerState.DISCONNECTED -> "DISCONNECTED"
                ListenerState.NEVER_OBSERVED -> null
            }
        } else {
            null
        }
        FacetSummary(
            facet = facet,
            state = memoryListenerState ?: latest?.kind?.facetState() ?: facet.defaultState(),
            lastObservedAtMillis = latest?.occurredAtMillis,
            isCurrentSessionObservation = latest != null &&
                latest.runtimeSessionId == query.currentRuntimeSessionId,
        )
    }

    private fun validateConnectionOwnership(
        evidence: List<HealthEvidence>,
        diagnostics: MutableSet<DerivationDiagnostic>,
    ): List<HealthEvidence> {
        val owners = hashMapOf<String, String>()
        val invalidConnections = hashSetOf<String>()
        evidence.forEach { item ->
            val connectionId = item.connectionId
            if (item.kind.isListenerConnectionEvidence() && connectionId == null) {
                diagnostics += DerivationDiagnostic.INVALID_CONNECTION_REFERENCE
            } else if (connectionId != null) {
                val previousOwner = owners.putIfAbsent(connectionId, item.runtimeSessionId)
                if (previousOwner != null && previousOwner != item.runtimeSessionId) {
                    diagnostics += DerivationDiagnostic.CONNECTION_REUSED_ACROSS_SESSIONS
                    invalidConnections += connectionId
                }
            }
        }
        return evidence.filter { item -> item.connectionId !in invalidConnections }
    }

    private fun detectNonMonotonicEvidence(
        evidence: List<HealthEvidence>,
        diagnostics: MutableSet<DerivationDiagnostic>,
    ): Set<String> {
        val nonMonotonicSessions = linkedSetOf<String>()
        evidence.groupBy(HealthEvidence::runtimeSessionId).values.forEach { sessionFacts ->
            val bySequence = sessionFacts.sortedBy(HealthEvidence::evidenceSequence)
            bySequence.zipWithNext().forEach { (left, right) ->
                val elapsedMovesForward = left.occurredElapsedMillis != null &&
                    right.occurredElapsedMillis != null &&
                    right.occurredElapsedMillis >= left.occurredElapsedMillis
                if (elapsedMovesForward && right.occurredAtMillis < left.occurredAtMillis) {
                    diagnostics += DerivationDiagnostic.NON_MONOTONIC_EVIDENCE
                    nonMonotonicSessions += right.runtimeSessionId
                }
            }
        }
        return nonMonotonicSessions
    }

    private fun firstBoundaryBetween(
        ordered: List<HealthEvidence>,
        connection: ConnectionKey,
        start: HealthEvidence,
        endAtMillis: Long,
    ): HealthEvidence? = ordered.firstOrNull { item ->
        isAfter(item, start) &&
            item.occurredAtMillis <= endAtMillis &&
            item.isBoundaryFor(connection)
    }

    private fun firstBoundaryAfter(
        ordered: List<HealthEvidence>,
        connection: ConnectionKey,
        start: HealthEvidence,
    ): HealthEvidence? = ordered.firstOrNull { item -> isAfter(item, start) && item.isBoundaryFor(connection) }

    private fun isAfter(candidate: HealthEvidence, reference: HealthEvidence): Boolean =
        candidate.occurredAtMillis > reference.occurredAtMillis ||
            candidate.occurredAtMillis == reference.occurredAtMillis &&
            candidate.evidenceSequence > reference.evidenceSequence

    private fun HealthEvidence.isBoundaryFor(connection: ConnectionKey): Boolean =
        kind == EvidenceKind.ACCESS_NOT_GRANTED ||
            kind == EvidenceKind.LISTENER_DISCONNECTED &&
            runtimeSessionId == connection.sessionId &&
            connectionId == connection.connectionId

    private fun addCandidate(
        candidates: MutableList<HealthInterval>,
        query: HealthQuery,
        start: HealthEvidence,
        boundary: HealthEvidence?,
        endAtMillis: Long,
        explanation: IntervalExplanation,
        isLive: Boolean,
    ) {
        val clippedStart = maxOf(start.occurredAtMillis, query.rangeStartMillis)
        val clippedEnd = minOf(endAtMillis, query.rangeEndMillis)
        if (clippedEnd <= clippedStart) return
        candidates += HealthInterval(
            startMillis = clippedStart,
            endMillis = clippedEnd,
            state = IntervalState.CONFIRMED_RUNNING,
            evidenceRefs = listOfNotNull(start.evidenceId, boundary?.evidenceId),
            connectionId = start.connectionId,
            isLiveProjection = isLive,
            explanation = explanation,
        )
    }

    private fun normalize(
        candidates: List<HealthInterval>,
        query: HealthQuery,
        diagnostics: MutableSet<DerivationDiagnostic>,
    ): List<HealthInterval> {
        val result = arrayListOf<HealthInterval>()
        var cursor = query.rangeStartMillis
        candidates.sortedWith(compareBy(HealthInterval::startMillis, HealthInterval::endMillis)).forEach { candidate ->
            if (candidate.endMillis <= cursor) return@forEach
            if (candidate.startMillis < cursor) {
                diagnostics += DerivationDiagnostic.OVERLAPPING_CONNECTION_EVIDENCE
            }
            val effectiveStart = maxOf(candidate.startMillis, cursor)
            if (effectiveStart > cursor) result += yellow(cursor, effectiveStart)
            if (candidate.endMillis > effectiveStart) {
                result += candidate.copy(startMillis = effectiveStart)
                cursor = candidate.endMillis
            }
        }
        if (cursor < query.rangeEndMillis) result += yellow(cursor, query.rangeEndMillis)
        return mergeEquivalent(result)
    }

    private fun yellow(start: Long, end: Long): HealthInterval = HealthInterval(
        startMillis = start,
        endMillis = end,
        state = IntervalState.UNCONFIRMED,
        evidenceRefs = emptyList(),
        connectionId = null,
        isLiveProjection = false,
        explanation = IntervalExplanation.INSUFFICIENT_EVIDENCE,
    )

    private fun mergeEquivalent(intervals: List<HealthInterval>): List<HealthInterval> {
        val result = arrayListOf<HealthInterval>()
        intervals.forEach { current ->
            val previous = result.lastOrNull()
            if (
                previous != null &&
                previous.endMillis == current.startMillis &&
                previous.state == current.state &&
                previous.connectionId == current.connectionId &&
                previous.isLiveProjection == current.isLiveProjection &&
                previous.explanation == current.explanation
            ) {
                result[result.lastIndex] = previous.copy(
                    endMillis = current.endMillis,
                    evidenceRefs = (previous.evidenceRefs + current.evidenceRefs).distinct(),
                )
            } else {
                result += current
            }
        }
        return result
    }

    private data class ConnectionKey(val sessionId: String, val connectionId: String)
}

private fun EvidenceKind.isPositiveListenerEvidence(): Boolean = when (this) {
    EvidenceKind.LISTENER_CONNECTED,
    EvidenceKind.NOTIFICATION_CALLBACK,
    EvidenceKind.RECOVERY_COMPLETED,
    -> true
    else -> false
}

private fun EvidenceKind.isListenerConnectionEvidence(): Boolean = when (this) {
    EvidenceKind.LISTENER_CONNECTED,
    EvidenceKind.LISTENER_DISCONNECTED,
    EvidenceKind.NOTIFICATION_CALLBACK,
    EvidenceKind.RECOVERY_COMPLETED,
    -> true
    else -> false
}

private fun EvidenceKind.facet(): HealthFacet = when (this) {
    EvidenceKind.PROCESS_STARTED,
    EvidenceKind.NORMAL_STOP,
    -> HealthFacet.PROCESS
    EvidenceKind.LISTENER_CONNECTED,
    EvidenceKind.LISTENER_DISCONNECTED,
    EvidenceKind.NOTIFICATION_CALLBACK,
    EvidenceKind.RECOVERY_COMPLETED,
    -> HealthFacet.LISTENER
    EvidenceKind.ACCESS_GRANTED,
    EvidenceKind.ACCESS_NOT_GRANTED,
    EvidenceKind.ACCESS_UNKNOWN,
    -> HealthFacet.LISTENER_ACCESS
    EvidenceKind.STATUS_PERMISSION_GRANTED,
    EvidenceKind.STATUS_PERMISSION_DENIED,
    -> HealthFacet.STATUS_PERMISSION
    EvidenceKind.STATUS_CHANNEL_ENABLED,
    EvidenceKind.STATUS_CHANNEL_DISABLED,
    -> HealthFacet.STATUS_CHANNEL
}

private fun EvidenceKind.facetState(): String = when (this) {
    EvidenceKind.PROCESS_STARTED -> "CURRENT_SESSION_ACTIVE"
    EvidenceKind.NORMAL_STOP -> "NO_CURRENT_SESSION_EVIDENCE"
    EvidenceKind.LISTENER_CONNECTED,
    EvidenceKind.NOTIFICATION_CALLBACK,
    EvidenceKind.RECOVERY_COMPLETED,
    -> "CONNECTED"
    EvidenceKind.LISTENER_DISCONNECTED -> "DISCONNECTED"
    EvidenceKind.ACCESS_GRANTED -> "GRANTED"
    EvidenceKind.ACCESS_NOT_GRANTED -> "NOT_GRANTED"
    EvidenceKind.ACCESS_UNKNOWN -> "UNKNOWN"
    EvidenceKind.STATUS_PERMISSION_GRANTED -> "GRANTED"
    EvidenceKind.STATUS_PERMISSION_DENIED -> "DENIED"
    EvidenceKind.STATUS_CHANNEL_ENABLED -> "ENABLED"
    EvidenceKind.STATUS_CHANNEL_DISABLED -> "DISABLED"
}

private fun HealthFacet.defaultState(): String = when (this) {
    HealthFacet.PROCESS -> "NO_CURRENT_SESSION_EVIDENCE"
    HealthFacet.LISTENER -> "NEVER_OBSERVED"
    HealthFacet.LISTENER_ACCESS -> "UNKNOWN"
    HealthFacet.STATUS_PERMISSION -> "UNKNOWN"
    HealthFacet.STATUS_CHANNEL -> "UNKNOWN"
}
