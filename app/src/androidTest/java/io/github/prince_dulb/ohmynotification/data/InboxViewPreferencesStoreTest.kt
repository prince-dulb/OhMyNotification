package io.github.prince_dulb.ohmynotification.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxViewPreferencesStoreTest {
    @Test
    fun appliedSourcesSurviveStoreRecreationAndCanBeRestored() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalStore = InboxViewPreferencesStore(context)
        val original = originalStore.filter.value
        val expected = linkedSetOf(
            AppUserKey("UserHandle{0}", "example.alpha"),
            AppUserKey("UserHandle{10}", "example.alpha"),
            AppUserKey("UserHandle{0}", "example.beta"),
        )

        try {
            val expectedFilter = InboxViewFilter(includedSources = expected)
            assertTrue(originalStore.setFilter(expectedFilter))
            assertEquals(expectedFilter, InboxViewPreferencesStore(context).filter.value)

            val excludedFilter = InboxViewFilter(excludedSources = setOf(expected.first()))
            assertTrue(originalStore.setFilter(excludedFilter))
            assertEquals(excludedFilter, InboxViewPreferencesStore(context).filter.value)
        } finally {
            assertTrue(InboxViewPreferencesStore(context).setFilter(original))
        }

        assertEquals(original, InboxViewPreferencesStore(context).filter.value)
    }
}
