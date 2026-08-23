package io.github.prince_dulb.ohmynotification.testsource

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.util.Properties
import java.util.UUID

internal class ControlledNotificationClient(private val instrumentation: Instrumentation) {
    fun execute(operation: String, caseId: String = ControlledNotificationProtocol.DEFAULT_CASE_ID): Result {
        require(operation in SUPPORTED_OPERATIONS) {
            "Unsupported controlled notification operation"
        }
        require(caseId.matches(Regex("^[a-z0-9-]+$"))) {
            "Controlled notification case ID contains unsupported characters"
        }
        val commandToken = UUID.randomUUID().toString()
        val startOutput = executeShellCommand(
            "am start -W -n $COMMAND_COMPONENT " +
                "--es ${ControlledNotificationProtocol.EXTRA_OPERATION} $operation " +
                "--es ${ControlledNotificationProtocol.EXTRA_CASE_ID} $caseId " +
                "--es ${ControlledNotificationProtocol.EXTRA_COMMAND_TOKEN} $commandToken",
        )
        check(startOutput.lineSequence().any { line -> line.trim() == "Status: ok" }) {
            "Controlled notification test activity could not be started"
        }

        val properties = awaitResult(commandToken)
        val status = properties.getProperty(ControlledNotificationResult.STATUS_KEY)
        check(status == ControlledNotificationResult.STATUS_OK) {
            "Controlled notification command failed: ${status ?: RESULT_MISSING}"
        }
        return Result(
            active = properties.requiredBoolean(ControlledNotificationProtocol.RESULT_ACTIVE),
            titleContainsMarker = properties.requiredBoolean(
                ControlledNotificationProtocol.RESULT_TITLE_CONTAINS_MARKER,
            ),
            titleEndsWithUpdate = properties.requiredBoolean(
                ControlledNotificationProtocol.RESULT_TITLE_ENDS_WITH_UPDATE,
            ),
            hasContentIntent = properties.requiredBoolean(
                ControlledNotificationProtocol.RESULT_HAS_CONTENT_INTENT,
            ),
            actionCount = properties.getProperty(ControlledNotificationProtocol.RESULT_ACTION_COUNT)
                ?.toIntOrNull()
                ?: error("Controlled notification result is missing action count"),
            targetOpened = properties.requiredBoolean(
                ControlledNotificationProtocol.RESULT_TARGET_OPENED,
            ),
        )
    }

    data class Result(
        val active: Boolean,
        val titleContainsMarker: Boolean,
        val titleEndsWithUpdate: Boolean,
        val hasContentIntent: Boolean,
        val actionCount: Int,
        val targetOpened: Boolean,
    )

    private fun executeShellCommand(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            command,
        )
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { reader -> reader.readText() }
    }

    private fun awaitResult(commandToken: String): Properties {
        val deadline = SystemClock.elapsedRealtime() + RESULT_TIMEOUT_MILLIS
        do {
            val properties = Properties().apply {
                executeShellCommand(
                    "run-as $TEST_PACKAGE cat files/${ControlledNotificationResult.FILE_NAME}",
                ).reader().use(::load)
            }
            val isCurrent =
                properties.getProperty(ControlledNotificationProtocol.RESULT_COMMAND_TOKEN) == commandToken
            val status = properties.getProperty(ControlledNotificationResult.STATUS_KEY)
            if (isCurrent && status != ControlledNotificationResult.STATUS_STARTED) {
                return properties
            }
            SystemClock.sleep(RESULT_POLL_INTERVAL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Controlled notification command did not produce a fresh terminal result")
    }

    private fun Properties.requiredBoolean(key: String): Boolean =
        when (val value = getProperty(key)) {
            "true" -> true
            "false" -> false
            else -> error("Controlled notification result has invalid boolean for $key: $value")
        }

    private companion object {
        const val TEST_PACKAGE = "io.github.prince_dulb.ohmynotification.test"
        const val COMMAND_COMPONENT =
            "$TEST_PACKAGE/io.github.prince_dulb.ohmynotification.testsource.ControlledNotificationCommandActivity"
        const val RESULT_MISSING = "MISSING_RESULT"
        const val RESULT_TIMEOUT_MILLIS = 3_000L
        const val RESULT_POLL_INTERVAL_MILLIS = 25L
        val SUPPORTED_OPERATIONS = setOf(
            ControlledNotificationProtocol.OPERATION_PUBLISH,
            ControlledNotificationProtocol.OPERATION_UPDATE,
            ControlledNotificationProtocol.OPERATION_REMOVE,
            ControlledNotificationProtocol.OPERATION_OPEN,
        )
    }
}
