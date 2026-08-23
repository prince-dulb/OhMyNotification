package io.github.prince_dulb.ohmynotification.testsource

internal object ControlledNotificationProtocol {
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
}
