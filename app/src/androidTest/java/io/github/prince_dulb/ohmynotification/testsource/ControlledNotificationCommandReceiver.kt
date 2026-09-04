package io.github.prince_dulb.ohmynotification.testsource

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

class ControlledNotificationCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) {
            setResult(Activity.RESULT_CANCELED, RESULT_UNSUPPORTED_ACTION, Bundle.EMPTY)
            return
        }

        val source = ControlledNotificationSource(context.applicationContext)
        try {
            when (intent.getStringExtra(EXTRA_OPERATION)) {
                OPERATION_PUBLISH -> source.publish(
                    caseId = intent.caseId(),
                    isUpdate = false,
                )
                OPERATION_UPDATE -> source.publish(
                    caseId = intent.caseId(),
                    isUpdate = true,
                )
                OPERATION_REMOVE -> source.remove()
                else -> {
                    setResult(Activity.RESULT_CANCELED, RESULT_UNSUPPORTED_OPERATION, Bundle.EMPTY)
                    return
                }
            }

            val active = source.activeNotification()?.notification
            val result = Bundle().apply {
                putBoolean(RESULT_ACTIVE, active != null)
                putBoolean(
                    RESULT_TITLE_CONTAINS_MARKER,
                    active?.extras
                        ?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                        ?.contains(ControlledNotificationSource.MARKER) == true,
                )
                putBoolean(
                    RESULT_TITLE_ENDS_WITH_UPDATE,
                    active?.extras
                        ?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                        ?.endsWith(" · update") == true,
                )
                putBoolean(RESULT_HAS_CONTENT_INTENT, active?.contentIntent != null)
                putInt(RESULT_ACTION_COUNT, active?.actions?.size ?: 0)
            }
            setResult(Activity.RESULT_OK, RESULT_OK, result)
        } catch (error: RuntimeException) {
            setResult(
                Activity.RESULT_CANCELED,
                "${RESULT_RUNTIME_FAILURE}:${error.javaClass.simpleName}",
                Bundle.EMPTY,
            )
        }
    }

    private fun Intent.caseId(): String =
        getStringExtra(EXTRA_CASE_ID)?.takeIf(String::isNotBlank) ?: DEFAULT_CASE_ID

    companion object {
        const val ACTION_COMMAND =
            "io.github.prince_dulb.ohmynotification.test.CONTROLLED_NOTIFICATION"
        const val EXTRA_OPERATION = "omn_operation"
        const val EXTRA_CASE_ID = "omn_case_id"
        const val EXTRA_COMMAND_TOKEN = "omn_command_token"
        const val OPERATION_PUBLISH = "publish"
        const val OPERATION_UPDATE = "update"
        const val OPERATION_REMOVE = "remove"
        const val OPERATION_OPEN = "open"
        const val DEFAULT_CASE_ID = "action"

        const val RESULT_ACTIVE = "active"
        const val RESULT_TITLE_CONTAINS_MARKER = "title_contains_marker"
        const val RESULT_TITLE_ENDS_WITH_UPDATE = "title_ends_with_update"
        const val RESULT_HAS_CONTENT_INTENT = "has_content_intent"
        const val RESULT_ACTION_COUNT = "action_count"
        const val RESULT_TARGET_OPENED = "target_opened"
        const val RESULT_COMMAND_TOKEN = "command_token"

        private const val RESULT_OK = "OK"
        private const val RESULT_UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION"
        private const val RESULT_UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION"
        private const val RESULT_RUNTIME_FAILURE = "RUNTIME_FAILURE"
    }
}
