package io.github.prince_dulb.ohmynotification.phase0

import android.app.ActivityOptions
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.prince_dulb.ohmynotification.R
import io.github.prince_dulb.ohmynotification.ui.theme.OhMyNotificationTheme

class PhaseZeroBilibiliActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OhMyNotificationTheme {
                val handleCount = remember {
                    RuntimeActionRegistry.countForCreator(BILIBILI_PACKAGE)
                }
                var dispatchStatus by remember {
                    mutableStateOf<RuntimeActionDispatchStatus?>(null)
                }
                Scaffold { contentPadding ->
                    VerificationContent(
                        contentPadding = contentPadding,
                        handleCount = handleCount,
                        dispatchStatus = dispatchStatus,
                        onDispatch = {
                            val options = ActivityOptions.makeBasic().apply {
                                pendingIntentBackgroundActivityStartMode =
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                            }
                            dispatchStatus = RuntimeActionRegistry.sendLatestForCreator(
                                creatorPackage = BILIBILI_PACKAGE,
                                options = options.toBundle(),
                            ).status
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val BILIBILI_PACKAGE = "tv.danmaku.bili"
    }
}

@androidx.compose.runtime.Composable
private fun VerificationContent(
    contentPadding: PaddingValues,
    handleCount: Int,
    dispatchStatus: RuntimeActionDispatchStatus?,
    onDispatch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.phase_zero_bilibili_action_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.phase_zero_bilibili_action_count, handleCount),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.phase_zero_bilibili_action_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            enabled = handleCount > 0,
            onClick = onDispatch,
        ) {
            Text(stringResource(R.string.phase_zero_bilibili_action_button))
        }
        Text(
            text = when {
                handleCount == 0 -> stringResource(R.string.phase_zero_bilibili_action_none)
                dispatchStatus == null -> stringResource(R.string.phase_zero_bilibili_action_ready)
                dispatchStatus == RuntimeActionDispatchStatus.ACCEPTED ->
                    stringResource(R.string.phase_zero_bilibili_action_accepted)
                dispatchStatus == RuntimeActionDispatchStatus.CANCELED ->
                    stringResource(R.string.phase_zero_bilibili_action_canceled)
                dispatchStatus == RuntimeActionDispatchStatus.NOT_FOUND ->
                    stringResource(R.string.phase_zero_bilibili_action_not_found)
                else -> stringResource(R.string.phase_zero_bilibili_action_security_rejected)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
