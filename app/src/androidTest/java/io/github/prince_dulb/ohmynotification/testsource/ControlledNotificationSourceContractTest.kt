package io.github.prince_dulb.ohmynotification.testsource

import android.app.Notification
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlledNotificationSourceContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val source = ControlledNotificationSource(context)

    @Before
    fun prepare() {
        grantNotificationPermission(context)
        source.remove()
        awaitAbsent()
    }

    @After
    fun cleanUp() {
        source.remove()
        awaitAbsent()
    }

    @Test
    fun publishUpdateAndRemoveUseOneIndependentSyntheticNotification() {
        source.publish(caseId = "action")
        val published = awaitActive().notification
        assertTrue(
            published.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                .contains(ControlledNotificationSource.MARKER),
        )
        assertNotNull(published.contentIntent)
        assertEquals(1, published.actions.size)

        source.publish(caseId = "action", isUpdate = true)
        val updated = awaitActive().notification
        assertTrue(
            updated.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                .endsWith(" · update"),
        )

        source.remove()
        awaitAbsent()
        assertNull(source.activeNotification())
    }

    private fun awaitActive() = eventually("notification to become active") {
        source.activeNotification()
    }

    private fun awaitAbsent() {
        eventually("notification to be removed") {
            if (source.activeNotification() == null) Unit else null
        }
    }

    private fun <T : Any> eventually(description: String, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        do {
            probe()?.let { result -> return result }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out waiting for $description")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 2_000L
        const val POLL_INTERVAL_MILLIS = 25L
    }
}
