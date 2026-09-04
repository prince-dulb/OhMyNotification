package io.github.prince_dulb.ohmynotification.testsource

import android.app.Activity
import android.app.Notification
import android.os.Bundle
import java.util.Properties

class ControlledNotificationBootstrapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Properties().apply {
            setProperty(RESULT_STATUS, STATUS_STARTED)
        }
        writeResult(result)
        try {
            val source = ControlledNotificationSource(applicationContext)
            result.setProperty(RESULT_STATUS, STATUS_SOURCE_CREATED)
            writeResult(result)
            when (intent.getStringExtra(ControlledNotificationCommandReceiver.EXTRA_OPERATION)) {
                ControlledNotificationCommandReceiver.OPERATION_PUBLISH -> source.publish(
                    caseId = intent.caseId(),
                    isUpdate = false,
                )
                ControlledNotificationCommandReceiver.OPERATION_UPDATE -> source.publish(
                    caseId = intent.caseId(),
                    isUpdate = true,
                )
                ControlledNotificationCommandReceiver.OPERATION_REMOVE -> source.remove()
                else -> error("Unsupported controlled notification operation")
            }
            result.setProperty(RESULT_STATUS, STATUS_OPERATION_COMPLETED)
            writeResult(result)
            val active = source.activeNotification()?.notification
            result.setProperty(RESULT_STATUS, STATUS_OK)
            result.setProperty(ControlledNotificationCommandReceiver.RESULT_ACTIVE, (active != null).toString())
            result.setProperty(
                ControlledNotificationCommandReceiver.RESULT_TITLE_CONTAINS_MARKER,
                (active?.extras
                    ?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.contains(ControlledNotificationSource.MARKER) == true).toString(),
            )
            result.setProperty(
                ControlledNotificationCommandReceiver.RESULT_TITLE_ENDS_WITH_UPDATE,
                (active?.extras
                    ?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.endsWith(" · update") == true).toString(),
            )
            result.setProperty(
                ControlledNotificationCommandReceiver.RESULT_HAS_CONTENT_INTENT,
                (active?.contentIntent != null).toString(),
            )
            result.setProperty(
                ControlledNotificationCommandReceiver.RESULT_ACTION_COUNT,
                (active?.actions?.size ?: 0).toString(),
            )
        } catch (error: Throwable) {
            result.clear()
            result.setProperty(RESULT_STATUS, "$STATUS_FAILURE:${error.javaClass.simpleName}")
        }
        writeResult(result)
        finish()
    }

    private fun writeResult(result: Properties) {
        openFileOutput(RESULT_FILE_NAME, MODE_PRIVATE).use { output ->
            result.store(output, null)
        }
    }

    private fun android.content.Intent.caseId(): String =
        getStringExtra(ControlledNotificationCommandReceiver.EXTRA_CASE_ID)
            ?.takeIf(String::isNotBlank)
            ?: ControlledNotificationCommandReceiver.DEFAULT_CASE_ID

    companion object {
        const val RESULT_FILE_NAME = "controlled-notification-result.properties"
        const val RESULT_STATUS = "status"
        const val STATUS_STARTED = "STARTED"
        const val STATUS_SOURCE_CREATED = "SOURCE_CREATED"
        const val STATUS_OPERATION_COMPLETED = "OPERATION_COMPLETED"
        const val STATUS_OK = "OK"
        const val STATUS_FAILURE = "FAILURE"
    }
}
