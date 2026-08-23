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
        val query = buildPageItemsFromSourcesQuery(
            setOf(
                AppUserKey("work", "example.same"),
                AppUserKey("personal", "example.other"),
                AppUserKey("personal", "example.same"),
            ),
        )
        val bindings = RecordingProgram()

        query.bindTo(bindings)

        assertEquals(
            "SELECT * FROM notification_items WHERE " +
                "(sourcePackage = ? AND sourceUserRef = ?) OR " +
                "(sourcePackage = ? AND sourceUserRef = ?) OR " +
                "(sourcePackage = ? AND sourceUserRef = ?) " +
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

    @Test(expected = IllegalArgumentException::class)
    fun emptySourceSetCannotCreateFilteredQuery() {
        buildPageItemsFromSourcesQuery(emptySet())
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
