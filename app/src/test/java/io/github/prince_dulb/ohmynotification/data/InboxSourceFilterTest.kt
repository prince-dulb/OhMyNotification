package io.github.prince_dulb.ohmynotification.data

import androidx.sqlite.db.SupportSQLiteProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxSourceFilterTest {
    @Test
    fun codecRoundTripsCompositeSourceWithoutDelimiterAmbiguity() {
        val source = AppUserKey("User:Handle{10}", "example.same")

        assertEquals(source, InboxSourceKeyCodec.decode(InboxSourceKeyCodec.encode(source)))
        assertNull(InboxSourceKeyCodec.decode("v1:not-a-length:anything"))
        assertNull(InboxSourceKeyCodec.decode("legacy.package"))
    }

    @Test
    fun queryBindsExactUserAndPackagePairsInStableOrder() {
        val query = buildPageItemsQuery(
            viewFilter = InboxViewFilter(
                includedSources = setOf(
                    AppUserKey("work", "example.same"),
                    AppUserKey("personal", "example.other"),
                    AppUserKey("personal", "example.same"),
                ),
            ),
            actionView = NotificationActionView.ACTIONABLE,
            runtimeActionItemIds = setOf(9L, 3L),
        )
        val bindings = RecordingProgram()

        query.bindTo(bindings)

        assertEquals(
            "SELECT * FROM notification_items WHERE itemId IN (3,9) AND (" +
                "(sourcePackage = ? AND sourceUserRef = ?) OR " +
                "(sourcePackage = ? AND sourceUserRef = ?) OR " +
                "(sourcePackage = ? AND sourceUserRef = ?)) " +
                "ORDER BY sortTimeEpochMillis DESC, itemId DESC",
            query.sql,
        )
        assertEquals(
            mapOf(
                1 to "example.other",
                2 to "personal",
                3 to "example.same",
                4 to "personal",
                5 to "example.same",
                6 to "work",
            ),
            bindings.values,
        )
    }

    @Test
    fun emptyRuntimeSetCreatesEmptyInboxAndFullArchive() {
        assertEquals(
            "SELECT * FROM notification_items WHERE 0 ORDER BY sortTimeEpochMillis DESC, itemId DESC",
            buildPageItemsQuery(
                viewFilter = InboxViewFilter(),
                actionView = NotificationActionView.ACTIONABLE,
                runtimeActionItemIds = emptySet(),
            ).sql,
        )
        assertEquals(
            "SELECT * FROM notification_items WHERE 1 ORDER BY sortTimeEpochMillis DESC, itemId DESC",
            buildPageItemsQuery(
                viewFilter = InboxViewFilter(),
                actionView = NotificationActionView.UNAVAILABLE_ARCHIVE,
                runtimeActionItemIds = emptySet(),
            ).sql,
        )
    }

    @Test
    fun archiveExcludesCurrentRuntimeActionsWithoutConsumingSqlBindings() {
        val query = buildPageItemsQuery(
            viewFilter = InboxViewFilter(
                includedSources = setOf(AppUserKey("personal", "example.same")),
            ),
            actionView = NotificationActionView.UNAVAILABLE_ARCHIVE,
            runtimeActionItemIds = setOf(12L, 7L),
        )
        val bindings = RecordingProgram()

        query.bindTo(bindings)

        assertEquals(
            "SELECT * FROM notification_items WHERE itemId NOT IN (7,12) AND " +
                "((sourcePackage = ? AND sourceUserRef = ?)) " +
                "ORDER BY sortTimeEpochMillis DESC, itemId DESC",
            query.sql,
        )
        assertEquals(mapOf(1 to "example.same", 2 to "personal"), bindings.values)
    }

    @Test
    fun excludedSourceUsesNegatedPairAndLeavesFutureSourcesVisible() {
        val query = buildPageItemsQuery(
            viewFilter = InboxViewFilter(
                excludedSources = setOf(AppUserKey("personal", "example.hidden")),
            ),
            actionView = NotificationActionView.UNAVAILABLE_ARCHIVE,
            runtimeActionItemIds = emptySet(),
        )
        val bindings = RecordingProgram()

        query.bindTo(bindings)

        assertEquals(
            "SELECT * FROM notification_items WHERE 1 AND NOT " +
                "((sourcePackage = ? AND sourceUserRef = ?)) " +
                "ORDER BY sortTimeEpochMillis DESC, itemId DESC",
            query.sql,
        )
        assertEquals(mapOf(1 to "example.hidden", 2 to "personal"), bindings.values)
    }

    @Test
    fun sourceShortcutsKeepWhitelistAndBlacklistMutuallyExclusive() {
        val source = AppUserKey("personal", "example.alpha")
        val other = AppUserKey("personal", "example.beta")

        assertEquals(
            InboxViewFilter(includedSources = setOf(source)),
            InboxViewFilter(excludedSources = setOf(other)).only(source),
        )
        assertEquals(
            InboxViewFilter(excludedSources = setOf(source)),
            InboxViewFilter().without(source),
        )
        assertEquals(
            InboxViewFilter(includedSources = setOf(other)),
            InboxViewFilter(includedSources = setOf(source, other)).without(source),
        )
    }

    private class RecordingProgram : SupportSQLiteProgram {
        val values = sortedMapOf<Int, Any?>()

        override fun bindNull(index: Int) {
            values[index] = null
        }

        override fun bindLong(index: Int, value: Long) {
            values[index] = value
        }

        override fun bindDouble(index: Int, value: Double) {
            values[index] = value
        }

        override fun bindString(index: Int, value: String) {
            values[index] = value
        }

        override fun bindBlob(index: Int, value: ByteArray) {
            values[index] = value
        }

        override fun clearBindings() {
            values.clear()
        }

        override fun close() = Unit
    }
}
