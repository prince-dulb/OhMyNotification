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
        val original = originalStore.includedSourcePackages.value
        val expected = linkedSetOf("example.alpha", "example.beta")

        try {
            assertTrue(originalStore.setIncludedSourcePackages(expected + ""))
            assertEquals(expected, InboxViewPreferencesStore(context).includedSourcePackages.value)
        } finally {
            assertTrue(InboxViewPreferencesStore(context).setIncludedSourcePackages(original))
        }

        assertEquals(original, InboxViewPreferencesStore(context).includedSourcePackages.value)
    }
}
