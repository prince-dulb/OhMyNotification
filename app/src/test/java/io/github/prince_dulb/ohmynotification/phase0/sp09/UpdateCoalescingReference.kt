package io.github.prince_dulb.ohmynotification.phase0.sp09

internal data class UpdateCoalescingState(
    val lastPersistedAtNanos: Long,
    val firstPendingAtNanos: Long? = null,
    val latestPendingAtNanos: Long? = null,
) {
    init {
        require((firstPendingAtNanos == null) == (latestPendingAtNanos == null))
    }

    val hasPendingUpdate: Boolean get() = firstPendingAtNanos != null
}

internal enum class UpdateCoalescingAction {
    PERSIST_CURRENT,
    HOLD_LATEST,
    FLUSH_PENDING,
    REMOVE_CURRENT,
    FLUSH_PENDING_THEN_REMOVE,
}

internal data class UpdateCoalescingDecision(
    val action: UpdateCoalescingAction,
    val state: UpdateCoalescingState?,
    val scheduleAfterNanos: Long? = null,
)

internal class UpdateCoalescingReference(
    private val quietWindowNanos: Long = 10_000_000_000L,
    private val maximumPendingNanos: Long = 60_000_000_000L,
) {
    init {
        require(quietWindowNanos > 0L)
        require(maximumPendingNanos >= quietWindowNanos)
    }

    fun onContent(
        previous: UpdateCoalescingState?,
        observedAtNanos: Long,
    ): UpdateCoalescingDecision {
        if (previous == null) {
            return UpdateCoalescingDecision(
                action = UpdateCoalescingAction.PERSIST_CURRENT,
                state = UpdateCoalescingState(lastPersistedAtNanos = observedAtNanos),
            )
        }
        if (!previous.hasPendingUpdate && observedAtNanos - previous.lastPersistedAtNanos >= maximumPendingNanos) {
            return UpdateCoalescingDecision(
                action = UpdateCoalescingAction.PERSIST_CURRENT,
                state = UpdateCoalescingState(lastPersistedAtNanos = observedAtNanos),
            )
        }
        val firstPendingAt = previous.firstPendingAtNanos ?: observedAtNanos
        return UpdateCoalescingDecision(
            action = UpdateCoalescingAction.HOLD_LATEST,
            state = previous.copy(
                firstPendingAtNanos = firstPendingAt,
                latestPendingAtNanos = observedAtNanos,
            ),
            scheduleAfterNanos = quietWindowNanos.takeIf { previous.firstPendingAtNanos == null },
        )
    }

    fun onTimer(
        previous: UpdateCoalescingState,
        nowNanos: Long,
    ): UpdateCoalescingDecision {
        val firstPendingAt = requireNotNull(previous.firstPendingAtNanos)
        val latestPendingAt = requireNotNull(previous.latestPendingAtNanos)
        val quietRemaining = quietWindowNanos - (nowNanos - latestPendingAt)
        val maximumRemaining = maximumPendingNanos - (nowNanos - firstPendingAt)
        val nextDelay = minOf(quietRemaining, maximumRemaining)
        return if (nextDelay <= 0L) {
            UpdateCoalescingDecision(
                action = UpdateCoalescingAction.FLUSH_PENDING,
                state = UpdateCoalescingState(lastPersistedAtNanos = nowNanos),
            )
        } else {
            UpdateCoalescingDecision(
                action = UpdateCoalescingAction.HOLD_LATEST,
                state = previous,
                scheduleAfterNanos = nextDelay,
            )
        }
    }

    fun onRemoval(previous: UpdateCoalescingState?): UpdateCoalescingDecision =
        UpdateCoalescingDecision(
            action = if (previous?.hasPendingUpdate == true) {
                UpdateCoalescingAction.FLUSH_PENDING_THEN_REMOVE
            } else {
                UpdateCoalescingAction.REMOVE_CURRENT
            },
            state = null,
        )
}
