package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Deprecated("Use OmnAppScreen with live platform state.")
@Composable
internal fun PhaseZeroScreen(modifier: Modifier = Modifier) {
    OmnAppScreen(
        state = AppUiState(),
        onOpenNotificationAccess = {},
        onRequestStatusNotification = {},
        modifier = modifier,
    )
}
