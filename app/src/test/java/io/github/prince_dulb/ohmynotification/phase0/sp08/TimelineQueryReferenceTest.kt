package io.github.prince_dulb.ohmynotification.phase0.sp08

import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineQueryReferenceTest {
    @Test
    fun baseOrderUsesTimeThenSequenceThenUnsignedCanonicalItemId() {
        val items = listOf(
            item("017f", A, minute = 10, sequence = 2),
            item("01ff", A, minute = 10, sequence = 2),
            item("0001", A, minute = 10, sequence = 3),
            item("ffff", A, minute = 11, sequence = 1),
        )

        assertEquals(
            listOf("ffff", "0001", "01ff", "017f"),
            items.sortedWith(TimelineItemOrder).map { it.itemId.hex },
        )
    }

    @Test
    fun comparatorIsAntisymmetricAndTransitiveForFixedSeedData() {
        val random = Random(SEED)
        val items = List(2_000) { index ->
            TimelineItem(
                itemId = CanonicalItemId.fromLong(index.toLong()),
                source = SOURCES[index % SOURCES.size],
                firstReceivedAtMillis = random.nextLong(0, 20),
                ingestSequence = random.nextLong(0, 50),
            )
        }
        repeat(10_000) {
            val a = items[random.nextInt(items.size)]
            val b = items[random.nextInt(items.size)]
            assertEquals(
                -TimelineItemOrder.compare(b, a).sign(),
                TimelineItemOrder.compare(a, b).sign(),
            )
        }
        repeat(10_000) {
            val a = items[random.nextInt(items.size)]
            val b = items[random.nextInt(items.size)]
            val c = items[random.nextInt(items.size)]
            if (TimelineItemOrder.compare(a, b) <= 0 && TimelineItemOrder.compare(b, c) <= 0) {
                assertTrue(TimelineItemOrder.compare(a, c) <= 0)
            }
        }
    }

    @Test
    fun keysetPagingIsInvariantAcrossPageSizesWithoutReturningAllTenThousandAtOnce() {
        val random = Random(SEED)
        val source = List(10_000) { index ->
            TimelineItem(
                itemId = CanonicalItemId.fromLong(index.toLong()),
                source = SOURCES[index % SOURCES.size],
                firstReceivedAtMillis = BASE_TIME - random.nextLong(0, 4_000),
                ingestSequence = index.toLong(),
            )
        }
        val expected = digestFor(source.sortedWith(TimelineItemOrder).asSequence())
        assertEquals(FULL_HISTORY_SEQUENCE_SHA256, expected)

        listOf(1, 10, 37, 100, 257).forEach { loadSize ->
            val query = ReferencePagedQuery(source)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0
            query.foldPages(InboxFilter(), loadSize) { value ->
                digest.update(value.itemId.hex.toByteArray(Charsets.UTF_8))
                count += 1
            }
            assertEquals(10_000, count)
            assertEquals(expected, digest.digest().toHex())
            assertTrue(query.peakReturnedItems <= loadSize)
            assertTrue(query.rowsExamined <= 10_000L + query.queryCount)
        }
    }

    @Test
    fun sourceFilterRunsBeforePagingAndPreservesBaseRelativeOrder() {
        val items = generatedItems(500)
        val filter = InboxFilter(setOf(A, C))
        val expected = items.sortedWith(TimelineItemOrder).filter(filter::matches)
        val actual = arrayListOf<TimelineItem>()
        ReferencePagedQuery(items).foldPages(filter, loadSize = 17, actual::add)

        assertEquals(expected.map(TimelineItem::itemId), actual.map(TimelineItem::itemId))
        assertTrue(actual.all(filter::matches))
        assertEquals(filter.fingerprint(), InboxFilter(setOf(C, A)).fingerprint())
        assertNotEquals(filter.fingerprint(), InboxFilter(setOf(A)).fingerprint())
    }

    @Test
    fun anchorV2CoversConfirmedGroupingBoundaries() {
        assertDrawer(
            listOf(item("01", A, 42), item("02", A, 41), item("03", A, 40)),
            expectedMembers = listOf("01", "02", "03"),
        )

        val shortInterleave = group(
            listOf(item("11", A, 42), item("12", B, 40), item("13", A, 35)),
        )
        assertEquals(listOf("D:11,13", "S:12"), shortInterleave.snapshot())

        val fourthOther = group(
            listOf(
                item("21", A, 42),
                item("22", B, 40),
                item("23", C, 39),
                item("24", D, 38),
                item("25", E, 37),
                item("26", A, 35),
            ),
        )
        assertTrue(fourthOther.none { it is AppDrawerNode && it.groupingKey == A })

        val overWindow = group(
            listOf(item("31", A, 42), item("32", B, 40), item("33", A, 26)),
        )
        assertTrue(overWindow.none { it is AppDrawerNode && it.groupingKey == A })

        assertDrawer(
            listOf(item("34", A, 4_000), item("35", A, 1)),
            expectedMembers = listOf("34", "35"),
        )

        val exactBoundary = group(
            listOf(
                item("41", A, 42),
                item("42", B, 41),
                item("43", C, 40),
                item("44", D, 39),
                item("45", A, 27),
            ),
        )
        assertEquals(listOf("41", "45"), exactBoundary.filterIsInstance<AppDrawerNode>().single().ids())

        val userIsolation = group(
            listOf(item("51", A, 42), item("52", A_WORK, 41)),
        )
        assertTrue(userIsolation.all { it is SingleItemNode })
    }

    @Test
    fun consumedOtherSourceStillCountsTowardInterveningLimit() {
        val nodes = group(
            listOf(
                item("61", A, 42),
                item("62", B, 41),
                item("63", A, 40),
                item("64", C, 39),
                item("65", D, 38),
                item("66", E, 37),
                item("67", B, 36),
            ),
        )

        assertEquals(listOf("61", "63"), nodes.filterIsInstance<AppDrawerNode>().single().ids())
        assertTrue(nodes.none { it is AppDrawerNode && it.groupingKey == B })
        assertEquals(7, nodes.flatMap(TimelineNode::flattenedMemberIds).distinct().size)
    }

    @Test
    fun filteringPrecedesGroupingAndHiddenItemsDoNotCountAsIntervening() {
        val all = listOf(item("71", A, 4_000), item("72", B, 40), item("73", A, 1))
        val visible = all.filter(InboxFilter(setOf(A))::matches)
        val nodes = group(visible)

        assertEquals(listOf("D:71,73"), nodes.snapshot())
    }

    @Test
    fun groupingIsDeterministicCompleteUniqueAndPageConcatenationInvariant() {
        val items = generatedItems(1_000).sortedWith(TimelineItemOrder)
        val expected = group(items)
        val expectedSnapshot = expected.snapshot()
        val expectedIds = items.map(TimelineItem::itemId)

        listOf(10, 37, 100).forEach { loadSize ->
            val paged = arrayListOf<TimelineItem>()
            ReferencePagedQuery(items).foldPages(InboxFilter(), loadSize, paged::add)
            assertEquals(expectedSnapshot, group(paged).snapshot())
        }

        val flattened = expected.flatMap(TimelineNode::flattenedMemberIds)
        assertEquals(expectedIds.toSet(), flattened.toSet())
        assertEquals(expectedIds.size, flattened.size)
        expected.filterIsInstance<AppDrawerNode>().forEach { drawer ->
            val memberItems = drawer.ids().map { id -> items.single { it.itemId.hex == id } }
            assertTrue(memberItems.all { it.source == drawer.groupingKey })
            assertEquals(memberItems.sortedWith(TimelineItemOrder), memberItems)
        }
    }

    @Test
    fun extremeSingleSourceBurstHasStableHeaderAndPagedMemberAccess() {
        val items = List(500) { index ->
            item(
                id = "%04x".format(index),
                source = A,
                minute = 42,
                sequence = (500 - index).toLong(),
            )
        }.sortedWith(TimelineItemOrder)
        val store = PagedTimelineStore(items, pageSize = 37, maxCachedPages = 2)
        val drawer = AnchorV2TimelineGrouper.group(
            store = store,
            queryFingerprint = InboxFilter().fingerprint(),
        ).single() as AppDrawerNode

        assertEquals(500, drawer.count)
        assertEquals(items.first().firstReceivedAtMillis, drawer.newestAtMillis)
        assertEquals(items.last().firstReceivedAtMillis, drawer.oldestAtMillis)
        assertTrue(store.peakResidentItems <= 74)
        val paged = buildList {
            var offset = 0
            while (offset < drawer.count) {
                val page = drawer.memberPage(offset, 37)
                assertTrue(page.size <= 37)
                addAll(page)
                offset += page.size
            }
        }
        assertEquals(items.map(TimelineItem::itemId), paged)
        assertTrue(store.peakResidentItems <= 74)
    }

    private fun assertDrawer(items: List<TimelineItem>, expectedMembers: List<String>) {
        val drawer = group(items).single() as AppDrawerNode
        assertEquals(expectedMembers, drawer.ids())
    }

    private fun group(items: List<TimelineItem>): List<TimelineNode> =
        AnchorV2TimelineGrouper.group(
            orderedItems = items.sortedWith(TimelineItemOrder),
            queryFingerprint = InboxFilter().fingerprint(),
        )

    private fun generatedItems(count: Int): List<TimelineItem> {
        val random = Random(SEED)
        return List(count) { index ->
            TimelineItem(
                itemId = CanonicalItemId.fromLong(index.toLong()),
                source = SOURCES[index % SOURCES.size],
                firstReceivedAtMillis = BASE_TIME - random.nextLong(0, 60 * 60 * 1_000L),
                ingestSequence = index.toLong(),
            )
        }
    }

    private fun item(
        id: String,
        source: AppUserKey,
        minute: Int,
        sequence: Long = minute.toLong(),
    ): TimelineItem = TimelineItem(
        itemId = CanonicalItemId.fromHex(id),
        source = source,
        firstReceivedAtMillis = minute * 60_000L,
        ingestSequence = sequence,
    )

    private fun List<TimelineNode>.snapshot(): List<String> = map { node ->
        when (node) {
            is SingleItemNode -> "S:${node.item.itemId.hex}"
            is AppDrawerNode -> "D:${node.ids().joinToString(separator = ",")}"
        }
    }

    private fun AppDrawerNode.ids(): List<String> = flattenedMemberIds().map(CanonicalItemId::hex)

    private fun digestFor(items: Sequence<TimelineItem>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        items.forEach { item -> digest.update(item.itemId.hex.toByteArray(Charsets.UTF_8)) }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun Int.sign(): Int = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }

    private companion object {
        const val SEED = 2026082308
        const val BASE_TIME = 2_000_000_000_000L
        const val FULL_HISTORY_SEQUENCE_SHA256 =
            "189ed16336e16041bd002364497ec80550f2f6987f92fcd90e8665b381f32d2e"
        val A = AppUserKey("user-0", "dev.omn.synthetic.alpha")
        val A_WORK = AppUserKey("user-work", "dev.omn.synthetic.alpha")
        val B = AppUserKey("user-0", "dev.omn.synthetic.beta")
        val C = AppUserKey("user-0", "dev.omn.synthetic.gamma")
        val D = AppUserKey("user-0", "dev.omn.synthetic.delta")
        val E = AppUserKey("user-0", "dev.omn.synthetic.epsilon")
        val SOURCES = listOf(A, B, C, D, E)
    }
}
