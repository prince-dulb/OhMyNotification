package io.github.prince_dulb.ohmynotification.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.prince_dulb.ohmynotification.OmnApplication
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import java.util.UUID
import kotlinx.coroutines.launch

class OmnNotificationListenerService : NotificationListenerService() {
    private val graph get() = (application as OmnApplication).graph

    @Volatile
    private var listenerConnectionId: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        val connectionId = UUID.randomUUID().toString()
        listenerConnectionId = connectionId
        ListenerRuntimeState.setConnected(true)
        graph.serialScope.launch {
            graph.repository.recordHealth(
                kind = "LISTENER_CONNECTED",
                occurredAtEpochMillis = System.currentTimeMillis(),
                runtimeSessionId = graph.runtimeSessionId,
                listenerConnectionId = connectionId,
            )
            graph.statusNotificationController.onConnectionChanged(
                isConnected = true,
                currentRecordCount = graph.repository.currentItemCount(),
            )
        }

        runCatching { activeNotifications?.toList().orEmpty() }
            .onSuccess { notifications ->
                notifications.forEach { notification ->
                    capture(notification, ObservedCallbackKind.RECOVERY_SNAPSHOT, null, connectionId)
                }
                graph.serialScope.launch {
                    graph.repository.recordHealth(
                        kind = "RECOVERY_COMPLETED",
                        occurredAtEpochMillis = System.currentTimeMillis(),
                        runtimeSessionId = graph.runtimeSessionId,
                        listenerConnectionId = connectionId,
                    )
                }
            }
    }

    override fun onListenerDisconnected() {
        val connectionId = listenerConnectionId
        listenerConnectionId = null
        ListenerRuntimeState.setConnected(false)
        graph.serialScope.launch {
            graph.repository.recordHealth(
                kind = "LISTENER_DISCONNECTED",
                occurredAtEpochMillis = System.currentTimeMillis(),
                runtimeSessionId = graph.runtimeSessionId,
                listenerConnectionId = connectionId,
            )
            graph.statusNotificationController.onConnectionChanged(isConnected = false)
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(
        statusBarNotification: StatusBarNotification,
        rankingMap: RankingMap,
    ) {
        capture(
            statusBarNotification,
            ObservedCallbackKind.POST_OR_UPDATE,
            removalReason = null,
            connectionId = listenerConnectionId ?: return,
        )
    }

    override fun onNotificationRemoved(
        statusBarNotification: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        capture(
            statusBarNotification,
            ObservedCallbackKind.REMOVED,
            removalReason = reason,
            connectionId = listenerConnectionId ?: return,
        )
    }

    override fun onDestroy() {
        ListenerRuntimeState.setConnected(false)
        super.onDestroy()
    }

    private fun capture(
        statusBarNotification: StatusBarNotification,
        callbackKind: ObservedCallbackKind,
        removalReason: Int?,
        connectionId: String,
    ) {
        val result = graph.snapshotFactory.capture(
            statusBarNotification = statusBarNotification,
            callbackKind = callbackKind,
            listenerConnectionId = connectionId,
            policy = graph.policyStore.snapshot(),
            removalReason = removalReason,
        )
        if (result !is SnapshotResult.Captured) return
        graph.serialScope.launch {
            val label = graph.sourceLabelResolver.resolve(result.observation.identity.sourcePackage)
            graph.repository.processCaptured(result, label)?.let(
                graph.statusNotificationController::onCommitted,
            )
        }
    }
}
