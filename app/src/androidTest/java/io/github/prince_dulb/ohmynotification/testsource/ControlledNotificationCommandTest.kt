package io.github.prince_dulb.ohmynotification.testsource

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        grantNotificationPermission(instrumentation.context)
        val client = ControlledNotificationClient(instrumentation)

        when (operation) {
            OPERATION_PUBLISH -> {
                assertTrue(client.execute(operation, arguments.caseId()).active)
            }
            OPERATION_UPDATE -> {
                assertTrue(client.execute(operation, arguments.caseId()).active)
            }
            OPERATION_REMOVE -> {
                assertFalse(client.execute(operation, arguments.caseId()).active)
            }
            OPERATION_OPEN -> {
                assertTrue(client.execute(operation, arguments.caseId()).targetOpened)
            }
            else -> error("Unsupported controlled-source operation: $operation")
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
        const val OPERATION_OPEN = "open"
        const val DEFAULT_CASE_ID = "action"
    }
}
