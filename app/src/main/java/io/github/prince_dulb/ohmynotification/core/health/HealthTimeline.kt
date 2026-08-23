package io.github.prince_dulb.ohmynotification.core.health

data class HealthFact(
    val evidenceId: Long,
    val kind: String,
    val occurredAtEpochMillis: Long,
    val runtimeSessionId: String,
    val listenerConnectionId: String?,
)

enum class HealthIntervalState { CONFIRMED_RUNNING, UNCONFIRMED }

data class HealthInterval(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val state: HealthIntervalState,
    val listenerConnectionId: String?,
    val isLiveProjection: Boolean,
)

data class HealthTimelineQuery(
    val rangeStartEpochMillis: Long,
    val rangeEndEpochMillis: Long,
    val currentRuntimeSessionId: String?,
    val currentListenerConnectionId: String?,
) {
    init {
        require(rangeEndEpochMillis > rangeStartEpochMillis)
    }
}

object HealthTimelineDeriver {
    fun derive(facts: List<HealthFact>, query: HealthTimelineQuery): List<HealthInterval> {
        val ordered = facts.sortedWith(compareBy(HealthFact::occurredAtEpochMillis, HealthFact::evidenceId))
        val candidates = arrayListOf<HealthInterval>()
        ordered.asSequence()
            .filter { fact -> fact.isPositiveListenerEvidence() }
            .filter { fact -> fact.listenerConnectionId != null }
            .groupBy { fact -> ConnectionKey(fact.runtimeSessionId, requireNotNull(fact.listenerConnectionId)) }
            .forEach { (connection, positives) ->
                positives.forEachIndexed { index, positive ->
                    val next = positives.getOrNull(index + 1)
                    val boundary = ordered.firstOrNull { fact ->
                        fact.isAfter(positive) &&
                            (next == null || !fact.isAfter(next)) &&
                            fact.isBoundaryFor(connection)
                    }
                    val end = when {
                        boundary != null -> boundary.occurredAtEpochMillis
                        next != null -> next.occurredAtEpochMillis
                        query.currentRuntimeSessionId == connection.runtimeSessionId &&
                            query.currentListenerConnectionId == connection.listenerConnectionId ->
                            query.rangeEndEpochMillis
                        else -> positive.occurredAtEpochMillis
                    }
                    val start = maxOf(positive.occurredAtEpochMillis, query.rangeStartEpochMillis)
                    val clippedEnd = minOf(end, query.rangeEndEpochMillis)
                    if (clippedEnd > start) {
                        candidates += HealthInterval(
                            startEpochMillis = start,
                            endEpochMillis = clippedEnd,
                            state = HealthIntervalState.CONFIRMED_RUNNING,
                            listenerConnectionId = connection.listenerConnectionId,
                            isLiveProjection = next == null && boundary == null &&
                                query.currentRuntimeSessionId == connection.runtimeSessionId &&
                                query.currentListenerConnectionId == connection.listenerConnectionId,
                        )
                    }
                }
            }

        return normalize(candidates, query)
    }

    fun isFullyConfirmed(
        intervals: List<HealthInterval>,
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): Boolean {
        if (endEpochMillis <= startEpochMillis) return false
        var cursor = startEpochMillis
        intervals.asSequence()
            .filter { interval -> interval.state == HealthIntervalState.CONFIRMED_RUNNING }
            .filter { interval -> interval.endEpochMillis > startEpochMillis && interval.startEpochMillis < endEpochMillis }
            .sortedBy(HealthInterval::startEpochMillis)
            .forEach { interval ->
                if (interval.startEpochMillis > cursor) return false
                cursor = maxOf(cursor, interval.endEpochMillis)
                if (cursor >= endEpochMillis) return true
            }
        return false
    }

    private fun normalize(
        candidates: List<HealthInterval>,
        query: HealthTimelineQuery,
    ): List<HealthInterval> {
        val result = arrayListOf<HealthInterval>()
        var cursor = query.rangeStartEpochMillis
        candidates.sortedWith(compareBy(HealthInterval::startEpochMillis, HealthInterval::endEpochMillis))
            .forEach { candidate ->
                if (candidate.endEpochMillis <= cursor) return@forEach
                val start = maxOf(cursor, candidate.startEpochMillis)
                if (start > cursor) result += yellow(cursor, start)
                if (candidate.endEpochMillis > start) {
                    result += candidate.copy(startEpochMillis = start)
                    cursor = candidate.endEpochMillis
                }
            }
        if (cursor < query.rangeEndEpochMillis) result += yellow(cursor, query.rangeEndEpochMillis)
        return result
    }

    private fun yellow(start: Long, end: Long) = HealthInterval(
        startEpochMillis = start,
        endEpochMillis = end,
        state = HealthIntervalState.UNCONFIRMED,
        listenerConnectionId = null,
        isLiveProjection = false,
    )

    private fun HealthFact.isPositiveListenerEvidence(): Boolean = kind in POSITIVE_KINDS

    private fun HealthFact.isAfter(other: HealthFact): Boolean =
        occurredAtEpochMillis > other.occurredAtEpochMillis ||
            occurredAtEpochMillis == other.occurredAtEpochMillis && evidenceId > other.evidenceId

    private fun HealthFact.isBoundaryFor(connection: ConnectionKey): Boolean =
        kind == "ACCESS_NOT_GRANTED" ||
            kind == "LISTENER_DISCONNECTED" &&
            runtimeSessionId == connection.runtimeSessionId &&
            listenerConnectionId == connection.listenerConnectionId

    private data class ConnectionKey(val runtimeSessionId: String, val listenerConnectionId: String)

    private val POSITIVE_KINDS = setOf(
        "LISTENER_CONNECTED",
        "NOTIFICATION_COMMITTED",
        "NOTIFICATION_REMOVED",
        "RECOVERY_COMPLETED",
    )
}
