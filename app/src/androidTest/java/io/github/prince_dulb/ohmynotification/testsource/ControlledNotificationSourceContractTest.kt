package io.github.prince_dulb.ohmynotification.testsource

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlledNotificationSourceContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val client = ControlledNotificationClient(instrumentation)

    @Before
    fun prepare() {
        grantNotificationPermission(testContext)
        assertFalse(
            client.execute(ControlledNotificationProtocol.OPERATION_REMOVE).active,
        )
    }

    @After
    fun cleanUp() {
        assertFalse(
            client.execute(ControlledNotificationProtocol.OPERATION_REMOVE).active,
        )
    }

    @Test
    fun publishUpdateAndRemoveUseOneIndependentSyntheticNotification() {
        val published = client.execute(
            operation = ControlledNotificationProtocol.OPERATION_PUBLISH,
            caseId = "action",
        )
        assertTrue(published.active)
        assertTrue(published.titleContainsMarker)
        assertTrue(published.hasContentIntent)
        assertEquals(1, published.actionCount)

        val updated = client.execute(
            operation = ControlledNotificationProtocol.OPERATION_UPDATE,
            caseId = "action",
        )
        assertTrue(updated.active)
        assertTrue(updated.titleEndsWithUpdate)

        val opened = client.execute(
            operation = ControlledNotificationProtocol.OPERATION_OPEN,
            caseId = "action",
        )
        assertTrue(opened.active)
        assertTrue(opened.targetOpened)

        assertFalse(
            client.execute(ControlledNotificationProtocol.OPERATION_REMOVE).active,
        )
    }
}
