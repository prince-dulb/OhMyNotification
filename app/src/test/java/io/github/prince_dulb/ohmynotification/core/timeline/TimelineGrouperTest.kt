package io.github.prince_dulb.ohmynotification.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineGrouperTest {
    @Test
    fun shortAbaSequenceCreatesDrawerAndPreservesOtherItem() {
        val result = TimelineGrouper.group(
            listOf(
                item(1, "a", minute = 15),
                item(2, "b", minute = 14),
                item(3, "a", minute = 13),
            ),
        )

        assertEquals(listOf(listOf(1L, 3L), listOf(2L)), result.map(TimelineGroup::memberItemIds))
    }

    @Test
    fun longAbaSequenceKeepsStrictGlobalOrder() {
        val result = TimelineGrouper.group(
            listOf(
                item(1, "a", minute = 31),
                item(2, "b", minute = 20),
                item(3, "a", minute = 15),
            ),
        )

        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), result.map(TimelineGroup::memberItemIds))
    }

    @Test
    fun consecutiveSameSourceIgnoresTimeWindow() {
        val result = TimelineGrouper.group(
            listOf(
                item(1, "a", minute = 4_000),
                item(2, "a", minute = 1),
            ),
        )

        assertEquals(listOf(listOf(1L, 2L)), result.map(TimelineGroup::memberItemIds))
    }

    @Test
    fun configuredWindowControlsOnlyGroupingAcrossOtherSources() {
        val items = listOf(
            item(1, "a", minute = 60),
            item(2, "b", minute = 45),
            item(3, "a", minute = 30),
        )

        val thirtyMinutes = TimelineGrouper.group(items, windowMillis = 30L * 60_000L)
        val fifteenMinutes = TimelineGrouper.group(items, windowMillis = 15L * 60_000L)

        assertEquals(listOf(listOf(1L, 3L), listOf(2L)), thirtyMinutes.map(TimelineGroup::memberItemIds))
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), fifteenMinutes.map(TimelineGroup::memberItemIds))
    }

    @Test
    fun filteredVisibleStreamAlsoMergesLongConsecutiveSameSource() {
        val visibleAfterFiltering = listOf(
            item(1, "a", minute = 4_000),
            item(3, "a", minute = 1),
        )

        val result = TimelineGrouper.group(visibleAfterFiltering)

        assertEquals(listOf(listOf(1L, 3L)), result.map(TimelineGroup::memberItemIds))
    }

    @Test
    fun fourthInterveningItemStopsDrawerScan() {
        val result = TimelineGrouper.group(
            listOf(
                item(1, "a", minute = 10),
                item(2, "b", minute = 9),
                item(3, "c", minute = 8),
                item(4, "d", minute = 7),
                item(5, "e", minute = 6),
                item(6, "a", minute = 5),
            ),
        )

        assertEquals(6, result.size)
        assertEquals((1L..6L).map(::listOf), result.map(TimelineGroup::memberItemIds))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInputThatIsNotNewestFirst() {
        TimelineGrouper.group(listOf(item(1, "a", 1), item(2, "b", 2)))
    }

    private fun item(id: Long, source: String, minute: Long) = TimelineGroupingCandidate(
        itemId = id,
        sourcePackage = source,
        sourceUserRef = "user-0",
        firstReceivedAtEpochMillis = minute * 60_000L,
    )
}
