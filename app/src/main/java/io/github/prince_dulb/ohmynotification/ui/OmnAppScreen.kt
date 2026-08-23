package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.prince_dulb.ohmynotification.R

data class AppUiState(
    val listenerAccessGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val statusNotificationGranted: Boolean = false,
)

@Composable
internal fun OmnAppScreen(
    state: AppUiState,
    onOpenNotificationAccess: () -> Unit,
    onRequestStatusNotification: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val healthy = state.listenerAccessGranted && state.listenerConnected
    val healthColor = if (healthy) ConfirmedBlue else UnconfirmedYellow
    val healthText = if (healthy) {
        stringResource(R.string.health_confirmed)
    } else {
        stringResource(R.string.health_unconfirmed)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.inbox_eyebrow),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }

            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .fillMaxHeight()
                            .background(healthColor)
                            .semantics {
                                contentDescription = healthText
                            },
                    )
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = healthText,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        HealthRow(
                            label = stringResource(R.string.health_notification_access),
                            available = state.listenerAccessGranted,
                        )
                        HealthRow(
                            label = stringResource(R.string.health_listener_connection),
                            available = state.listenerConnected,
                        )
                        HealthRow(
                            label = stringResource(R.string.health_status_notification),
                            available = state.statusNotificationGranted,
                        )
                    }
                }
            }

            if (!state.listenerAccessGranted) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenNotificationAccess,
                ) {
                    Text(stringResource(R.string.action_grant_notification_access))
                }
            }
            if (!state.statusNotificationGranted) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRequestStatusNotification,
                ) {
                    Text(stringResource(R.string.action_grant_status_notification))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(20.dp),
                    )
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.inbox_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.inbox_empty_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, available: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = if (available) {
                stringResource(R.string.health_available)
            } else {
                stringResource(R.string.health_unavailable)
            },
            color = if (available) ConfirmedBlue else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private val ConfirmedBlue = Color(0xFF1565C0)
private val UnconfirmedYellow = Color(0xFFF9A825)
