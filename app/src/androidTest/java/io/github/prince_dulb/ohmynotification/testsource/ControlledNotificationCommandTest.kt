package io.github.prince_dulb.ohmynotification.testsource

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlledNotificationCommandTest {
    @Test
    fun executeCommand() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val operation = arguments.getString(ARG_OPERATION)
        assumeTrue("No controlled-source command requested", !operation.isNullOrBlank())

        val context = instrumentation.context
        val source = ControlledNotificationSource(context)
        grantNotificationPermission(context)

        when (operation) {
            OPERATION_PUBLISH -> {
                source.publish(arguments.caseId(), isUpdate = false)
                awaitActive(source)
            }
            OPERATION_UPDATE -> {
                source.publish(arguments.caseId(), isUpdate = true)
                awaitActive(source)
            }
            OPERATION_REMOVE -> {
                source.remove()
                val deadline = SystemClock.elapsedRealtime() + REMOVE_TIMEOUT_MILLIS
                while (source.activeNotification() != null && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(25L)
                }
                assertNull(source.activeNotification())
            }
            else -> error("Unsupported controlled-source operation: $operation")
        }
    }

    private fun awaitActive(source: ControlledNotificationSource) {
        val deadline = SystemClock.elapsedRealtime() + ACTIVE_TIMEOUT_MILLIS
        while (source.activeNotification() == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25L)
        }
        requireNotNull(source.activeNotification()) {
            "Synthetic notification did not become active"
        }
    }

    private fun android.os.Bundle.caseId(): String =
        getString(ARG_CASE_ID)?.takeIf(String::isNotBlank) ?: DEFAULT_CASE_ID

    private companion object {
        const val ARG_OPERATION = "omn_operation"
        const val ARG_CASE_ID = "omn_case_id"
        const val OPERATION_PUBLISH = "publish"
        const val OPERATION_UPDATE = "update"
        const val OPERATION_REMOVE = "remove"
        const val DEFAULT_CASE_ID = "action"
        const val ACTIVE_TIMEOUT_MILLIS = 2_000L
        const val REMOVE_TIMEOUT_MILLIS = 2_000L
    }
}
