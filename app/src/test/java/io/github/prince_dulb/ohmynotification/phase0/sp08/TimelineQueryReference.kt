package io.github.prince_dulb.ohmynotification.phase0.sp08

import java.security.MessageDigest

internal data class AppUserKey(
    val sourceUser: String,
    val sourcePackage: String,
) {
    init {
        require(sourceUser.isNotBlank())
        require(sourcePackage.isNotBlank())
    }
}

internal class CanonicalItemId private constructor(val hex: String) : Comparable<CanonicalItemId> {
    init {
        require(hex.length % 2 == 0)
        require(hex.matches(Regex("^[0-9a-f]+$")))
    }

    override fun compareTo(other: CanonicalItemId): Int = hex.compareTo(other.hex)

    override fun equals(other: Any?): Boolean = other is CanonicalItemId && hex == other.hex

    override fun hashCode(): Int = hex.hashCode()

    override fun toString(): String = hex

    companion object {
        fun fromHex(value: String): CanonicalItemId = CanonicalItemId(value)

        fun fromLong(value: Long): CanonicalItemId = CanonicalItemId("%016x".format(value))
    }
}

internal data class TimelineItem(
    val itemId: CanonicalItemId,
    val source: AppUserKey,
    val firstReceivedAtMillis: Long,
    val ingestSequence: Long,
)

internal data class SortCursor(
    val firstReceivedAtMillis: Long,
    val ingestSequence: Long,
    val itemId: CanonicalItemId,
)

internal object TimelineItemOrder : Comparator<TimelineItem> {
    override fun compare(left: TimelineItem, right: TimelineItem): Int =
        compareKeyParts(
            left.firstReceivedAtMillis,
            left.ingestSequence,
            left.itemId,
            right.firstReceivedAtMillis,
            right.ingestSequence,
            right.itemId,
        )

    fun compareToCursor(item: TimelineItem, cursor: SortCursor): Int =
        compareKeyParts(
            item.firstReceivedAtMillis,
            item.ingestSequence,
            item.itemId,
            cursor.firstReceivedAtMillis,
            cursor.ingestSequence,
            cursor.itemId,
        )

    private fun compareKeyParts(
        leftTime: Long,
        leftSequence: Long,
        leftId: CanonicalItemId,
        rightTime: Long,
        rightSequence: Long,
        rightId: CanonicalItemId,
    ): Int {
        val time = rightTime.compareTo(leftTime)
        if (time != 0) return time
        val sequence = rightSequence.compareTo(leftSequence)
        if (sequence != 0) return sequence
        return rightId.compareTo(leftId)
    }
}

internal data class InboxFilter(val includedSources: Set<AppUserKey> = emptySet()) {
    fun matches(item: TimelineItem): Boolean = includedSources.isEmpty() || item.source in includedSources

    fun fingerprint(): String {
        val canonical = if (includedSources.isEmpty()) {
            "ALL"
        } else {
            includedSources
                .sortedWith(compareBy(AppUserKey::sourceUser, AppUserKey::sourcePackage))
                .joinToString(separator = "\n") { source ->
                    "${source.sourceUser}\u0000${source.sourcePackage}"
                }
        }
        return sha256(canonical)
    }
}

internal class ReferencePagedQuery(items: List<TimelineItem>) {
    private val storedItems = items.sortedWith(TimelineItemOrder)

    init {
        require(storedItems.map(TimelineItem::itemId).toSet().size == storedItems.size) {
            "Duplicate itemId"
        }
    }

    var peakReturnedItems: Int = 0
        private set
    var queryCount: Int = 0
        private set
    var rowsExamined: Long = 0
        private set

    fun loadAfter(
        filter: InboxFilter,
        cursor: SortCursor?,
        loadSize: Int,
    ): Page {
        require(loadSize > 0)
        queryCount += 1
        val candidates = ArrayList<TimelineItem>(loadSize + 1)
        var index = cursor?.let(::firstIndexAfter) ?: 0
        while (index < storedItems.size) {
            val item = storedItems[index]
            index += 1
            rowsExamined += 1
            if (!filter.matches(item)) continue
            candidates += item
            if (candidates.size > loadSize) break
        }
        val hasMore = candidates.size > loadSize
        val pageItems = if (hasMore) candidates.subList(0, loadSize).toList() else candidates
        peakReturnedItems = maxOf(peakReturnedItems, pageItems.size)
        return Page(
            items = pageItems,
            nextCursor = pageItems.lastOrNull()?.sortCursor(),
            endReached = !hasMore,
        )
    }

    private fun firstIndexAfter(cursor: SortCursor): Int {
        var low = 0
        var high = storedItems.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (TimelineItemOrder.compareToCursor(storedItems[middle], cursor) <= 0) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    fun foldPages(
        filter: InboxFilter,
        loadSize: Int,
        consume: (TimelineItem) -> Unit,
    ) {
        var cursor: SortCursor? = null
        do {
            val page = loadAfter(filter, cursor, loadSize)
            page.items.forEach(consume)
            cursor = page.nextCursor
        } while (!page.endReached)
    }

    data class Page(
        val items: List<TimelineItem>,
        val nextCursor: SortCursor?,
        val endReached: Boolean,
    )
}

internal interface IndexedTimelineStore {
    val size: Int
    operator fun get(index: Int): TimelineItem
}

internal class PagedTimelineStore(
    items: List<TimelineItem>,
    private val pageSize: Int,
    private val maxCachedPages: Int = 2,
) : IndexedTimelineStore {
    private val storedItems = items.sortedWith(TimelineItemOrder)
    private val cache = object : LinkedHashMap<Int, List<TimelineItem>>(maxCachedPages, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, List<TimelineItem>>?,
        ): Boolean = size > maxCachedPages
    }

    init {
        require(pageSize > 0)
        require(maxCachedPages > 0)
    }

    override val size: Int = storedItems.size
    var peakResidentItems: Int = 0
        private set
    var pageLoadCount: Int = 0
        private set

    override fun get(index: Int): TimelineItem {
        require(index in 0 until size)
        val pageIndex = index / pageSize
        val page = cache.getOrPut(pageIndex) {
            pageLoadCount += 1
            val start = pageIndex * pageSize
            storedItems.subList(start, minOf(start + pageSize, size)).toList()
        }
        peakResidentItems = maxOf(peakResidentItems, cache.values.sumOf(List<TimelineItem>::size))
        return page[index % pageSize]
    }
}

private class ListTimelineStore(private val items: List<TimelineItem>) : IndexedTimelineStore {
    override val size: Int = items.size
    override fun get(index: Int): TimelineItem = items[index]
}

internal sealed interface TimelineNode {
    val anchorItemId: CanonicalItemId
    fun flattenedMemberIds(): List<CanonicalItemId>
}

internal data class SingleItemNode(val item: TimelineItem) : TimelineNode {
    override val anchorItemId: CanonicalItemId = item.itemId
    override fun flattenedMemberIds(): List<CanonicalItemId> = listOf(item.itemId)
}

internal class AppDrawerNode(
    val groupId: String,
    val groupingKey: AppUserKey,
    override val anchorItemId: CanonicalItemId,
    private val members: DrawerMemberLocator,
    val newestAtMillis: Long,
    val oldestAtMillis: Long,
    val count: Int,
) : TimelineNode {
    init {
        require(count >= 2)
    }

    override fun flattenedMemberIds(): List<CanonicalItemId> = buildList {
        var offset = 0
        while (offset < count) {
            val page = memberPage(offset, 64)
            check(page.isNotEmpty())
            addAll(page)
            offset += page.size
        }
    }

    fun memberPage(offset: Int, loadSize: Int): List<CanonicalItemId> {
        require(offset >= 0)
        require(loadSize > 0)
        return members.load(offset, loadSize)
    }
}

internal class DrawerMemberLocator(
    private val store: IndexedTimelineStore,
    private val range: IntRange,
    private val groupingKey: AppUserKey,
    excludedIndexes: List<Int>,
) {
    private val excluded = excludedIndexes.toHashSet()

    fun load(offset: Int, loadSize: Int): List<CanonicalItemId> {
        var matched = 0
        val result = ArrayList<CanonicalItemId>(loadSize)
        for (index in range) {
            if (index in excluded) continue
            val item = store[index]
            if (item.source != groupingKey) continue
            if (matched < offset) {
                matched += 1
                continue
            }
            result += item.itemId
            if (result.size == loadSize) break
        }
        return result
    }
}

internal object AnchorV1TimelineGrouper {
    const val WINDOW_MILLIS = 15L * 60L * 1_000L
    const val MAX_INTERVENING_OTHER_ITEMS = 3
    const val STRATEGY_VERSION = "ANCHOR_V1"

    fun group(
        orderedItems: List<TimelineItem>,
        queryFingerprint: String,
    ): List<TimelineNode> {
        require(orderedItems == orderedItems.sortedWith(TimelineItemOrder)) {
            "Input must use the A06 base order"
        }
        require(orderedItems.map(TimelineItem::itemId).toSet().size == orderedItems.size) {
            "Duplicate itemId"
        }
        return group(ListTimelineStore(orderedItems), queryFingerprint)
    }

    fun group(
        store: IndexedTimelineStore,
        queryFingerprint: String,
    ): List<TimelineNode> {
        val consumed = BooleanArray(store.size)
        val nodes = ArrayList<TimelineNode>()
        var anchorIndex = 0
        while (true) {
            while (anchorIndex < store.size && consumed[anchorIndex]) anchorIndex += 1
            if (anchorIndex >= store.size) break
            val anchor = store[anchorIndex]
            val groupDigest = newGroupDigest(queryFingerprint, anchor.source)
            groupDigest.update(anchor.itemId.hex.toByteArray(Charsets.UTF_8))
            var memberCount = 1
            var oldestAtMillis = anchor.firstReceivedAtMillis
            var interveningOtherItems = 0
            val excludedSameSourceIndexes = arrayListOf<Int>()
            var scanIndex = anchorIndex + 1
            while (scanIndex < store.size) {
                val candidate = store[scanIndex]
                if (anchor.firstReceivedAtMillis - candidate.firstReceivedAtMillis > WINDOW_MILLIS) break
                if (candidate.source != anchor.source) {
                    if (interveningOtherItems == MAX_INTERVENING_OTHER_ITEMS) break
                    interveningOtherItems += 1
                } else if (!consumed[scanIndex]) {
                    memberCount += 1
                    oldestAtMillis = candidate.firstReceivedAtMillis
                    groupDigest.update('\n'.code.toByte())
                    groupDigest.update(candidate.itemId.hex.toByteArray(Charsets.UTF_8))
                } else {
                    excludedSameSourceIndexes += scanIndex
                }
                scanIndex += 1
            }

            if (memberCount >= 2) {
                for (index in anchorIndex until scanIndex) {
                    if (!consumed[index] && store[index].source == anchor.source) consumed[index] = true
                }
                nodes += AppDrawerNode(
                    groupId = groupDigest.digest().toHex(),
                    groupingKey = anchor.source,
                    anchorItemId = anchor.itemId,
                    members = DrawerMemberLocator(
                        store = store,
                        range = anchorIndex until scanIndex,
                        groupingKey = anchor.source,
                        excludedIndexes = excludedSameSourceIndexes,
                    ),
                    newestAtMillis = anchor.firstReceivedAtMillis,
                    oldestAtMillis = oldestAtMillis,
                    count = memberCount,
                )
            } else {
                consumed[anchorIndex] = true
                nodes += SingleItemNode(anchor)
            }
        }
        return nodes
    }

    private fun newGroupDigest(
        queryFingerprint: String,
        source: AppUserKey,
    ): MessageDigest = MessageDigest.getInstance("SHA-256").apply {
        update(STRATEGY_VERSION.toByteArray(Charsets.UTF_8))
        update('\n'.code.toByte())
        update(queryFingerprint.toByteArray(Charsets.UTF_8))
        update('\n'.code.toByte())
        update(source.sourceUser.toByteArray(Charsets.UTF_8))
        update(0)
        update(source.sourcePackage.toByteArray(Charsets.UTF_8))
        update('\n'.code.toByte())
    }
}

internal fun TimelineItem.sortCursor(): SortCursor = SortCursor(
    firstReceivedAtMillis = firstReceivedAtMillis,
    ingestSequence = ingestSequence,
    itemId = itemId,
)

internal fun sha256(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
