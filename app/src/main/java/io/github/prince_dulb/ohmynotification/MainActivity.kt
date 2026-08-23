package io.github.prince_dulb.ohmynotification

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.prince_dulb.ohmynotification.capture.ListenerRuntimeState
import io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerComponent
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStatus
import io.github.prince_dulb.ohmynotification.data.NotificationItemEntity
import io.github.prince_dulb.ohmynotification.ui.AppUiState
import io.github.prince_dulb.ohmynotification.ui.OmnAppScreen
import io.github.prince_dulb.ohmynotification.ui.theme.OhMyNotificationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val uiState = mutableStateOf(AppUiState())
    private val connectionListener: (Boolean) -> Unit = {
        runOnUiThread(::refreshUiState)
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshUiState()
        if (granted) {
            val graph = (application as OmnApplication).graph
            graph.serialScope.launch {
                graph.statusNotificationController.onExternalStateChanged(
                    currentRecordCount = graph.repository.currentItemCount(),
                    latestItem = graph.repository.latestItem(),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OhMyNotificationTheme {
                OmnAppScreen(
                    state = uiState.value,
                    repository = (application as OmnApplication).graph.repository,
                    currentRuntimeSessionId = (application as OmnApplication).graph.runtimeSessionId,
                    currentListenerConnectionId = ListenerRuntimeState.connectionId(),
                    loadLaunchableSources =
                        (application as OmnApplication).graph.sourceLabelResolver::launchableSources,
                    onOpenNotificationAccess = ::openNotificationAccessSettings,
                    onRequestStatusNotification = ::requestStatusNotificationPermission,
                    onOpenStatusChannel = ::openStatusNotificationChannel,
                    onSetSourceExcluded = ::setSourceExcluded,
                    onOpenNotification = ::openNotification,
                    onOpenSourceApp = ::openSourceApp,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ListenerRuntimeState.addListener(connectionListener)
        refreshUiState()
    }

    override fun onResume() {
        super.onResume()
        refreshUiState()
    }

    override fun onStop() {
        ListenerRuntimeState.removeListener(connectionListener)
        super.onStop()
    }

    private fun refreshUiState() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val graph = (application as OmnApplication).graph
        uiState.value = AppUiState(
            listenerAccessGranted = notificationManager.isNotificationListenerAccessGranted(
                OmnNotificationListenerComponent.componentName(this),
            ),
            listenerConnected = ListenerRuntimeState.isConnected(),
            listenerConnectionObserved = ListenerRuntimeState.hasObservedConnectionState(),
            statusNotificationGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            statusNotificationChannelEnabled =
                graph.statusNotificationController.isChannelEnabled(),
        )
        graph.serialScope.launch { graph.recordCurrentHealthFacets() }
        graph.serialScope.launch {
            graph.statusNotificationController.onExternalStateChanged(
                currentRecordCount = graph.repository.currentItemCount(),
                latestItem = graph.repository.latestItem(),
            )
        }
    }

    private fun openNotificationAccessSettings() {
        val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                OmnNotificationListenerComponent.componentName(this@MainActivity),
            )
        }
        try {
            startActivity(detailIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun requestStatusNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openStatusNotificationChannel() {
        startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(
                    Settings.EXTRA_CHANNEL_ID,
                    (application as OmnApplication).graph.statusNotificationController.channelId(),
                )
            },
        )
    }

    private fun setSourceExcluded(sourcePackage: String, excluded: Boolean) {
        (application as OmnApplication).graph.serialScope.launch {
            (application as OmnApplication).graph.repository.setSourceExcluded(sourcePackage, excluded)
        }
    }

    private fun openNotification(item: NotificationItemEntity): RuntimeActionStatus =
        (application as OmnApplication).graph.repository.sendRuntimeAction(item)

    private fun openSourceApp(sourcePackage: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(sourcePackage) ?: return false
        return try {
            startActivity(launchIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
