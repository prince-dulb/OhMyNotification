package io.github.prince_dulb.ohmynotification.core.timeline

data class TimelineGroupingCandidate(
    val itemId: Long,
    val sourcePackage: String,
    val sourceUserRef: String,
    val firstReceivedAtEpochMillis: Long,
)

data class TimelineGroup(val memberItemIds: List<Long>) {
    init {
        require(memberItemIds.isNotEmpty())
    }

    val anchorItemId: Long get() = memberItemIds.first()
}

object TimelineGrouper {
    const val WINDOW_MILLIS = 15L * 60L * 1_000L
    const val MAX_INTERVENING_OTHER_ITEMS = 3

    fun group(
        orderedItems: List<TimelineGroupingCandidate>,
        windowMillis: Long = WINDOW_MILLIS,
        maxInterveningOtherItems: Int = MAX_INTERVENING_OTHER_ITEMS,
    ): List<TimelineGroup> {
        require(windowMillis >= 0L)
        require(maxInterveningOtherItems >= 0)
        require(orderedItems.map(TimelineGroupingCandidate::itemId).toSet().size == orderedItems.size)
        require(orderedItems.zipWithNext().all { (newer, older) ->
            newer.firstReceivedAtEpochMillis >= older.firstReceivedAtEpochMillis
        }) { "Input must be ordered newest first." }

        val consumed = BooleanArray(orderedItems.size)
        val groups = arrayListOf<TimelineGroup>()
        orderedItems.indices.forEach { anchorIndex ->
            if (consumed[anchorIndex]) return@forEach
            val anchor = orderedItems[anchorIndex]
            val memberIndexes = arrayListOf(anchorIndex)
            var otherApps = 0
            var scan = anchorIndex + 1
            while (scan < orderedItems.size) {
                val candidate = orderedItems[scan]
                val sameSource =
                    candidate.sourcePackage == anchor.sourcePackage &&
                    candidate.sourceUserRef == anchor.sourceUserRef
                val outsideWindow =
                    anchor.firstReceivedAtEpochMillis - candidate.firstReceivedAtEpochMillis > windowMillis
                if (outsideWindow && (!sameSource || otherApps > 0)) break
                if (sameSource) {
                    if (!consumed[scan]) memberIndexes += scan
                } else {
                    if (otherApps == maxInterveningOtherItems) break
                    otherApps += 1
                }
                scan += 1
            }

            if (memberIndexes.size > 1) {
                memberIndexes.forEach { memberIndex -> consumed[memberIndex] = true }
            } else {
                consumed[anchorIndex] = true
            }
            groups += TimelineGroup(memberIndexes.map { index -> orderedItems[index].itemId })
        }
        return groups
    }
}
