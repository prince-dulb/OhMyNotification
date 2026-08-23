package io.github.prince_dulb.ohmynotification.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.prince_dulb.ohmynotification.OmnApplication
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class OmnNotificationListenerService : NotificationListenerService() {
    private val graph get() = (application as OmnApplication).graph
    private val workQueue = Channel<ListenerWork>(capacity = WORK_QUEUE_CAPACITY)

    @Volatile
    private var listenerConnectionId: String? = null

    override fun onCreate() {
        super.onCreate()
        graph.serialScope.launch {
            for (work in workQueue) process(work)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val connectionId = UUID.randomUUID().toString()
        listenerConnectionId = connectionId
        ListenerRuntimeState.setConnected(true, connectionId)
        enqueue(ListenerWork.Connected(connectionId, System.currentTimeMillis()))

        runCatching { activeNotifications?.toList().orEmpty() }
            .onSuccess { notifications ->
                notifications.sortedBy(StatusBarNotification::getPostTime).forEach { notification ->
                    capture(notification, ObservedCallbackKind.RECOVERY_SNAPSHOT, null, connectionId)
                }
                enqueue(ListenerWork.RecoveryCompleted(connectionId, System.currentTimeMillis()))
            }
            .onFailure {
                enqueue(ListenerWork.RecoveryFailed(connectionId, System.currentTimeMillis()))
            }
    }

    override fun onListenerDisconnected() {
        val connectionId = listenerConnectionId
        listenerConnectionId = null
        ListenerRuntimeState.setConnected(false)
        enqueue(ListenerWork.Disconnected(connectionId, System.currentTimeMillis()))
        graph.serialScope.launch {
            delay(REBIND_DELAY_MILLIS)
            if (!ListenerRuntimeState.isConnected()) {
                graph.listenerRebindController.request(ListenerRebindTrigger.AUTOMATIC)
            }
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
        listenerConnectionId?.let { connectionId ->
            enqueue(ListenerWork.Disconnected(connectionId, System.currentTimeMillis()))
        }
        listenerConnectionId = null
        workQueue.close()
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
        enqueue(ListenerWork.Captured(result))
    }

    private fun enqueue(work: ListenerWork) {
        if (workQueue.trySend(work).isSuccess) return
        runCatching {
            runBlocking { workQueue.send(work) }
        }
    }

    private suspend fun process(work: ListenerWork) {
        when (work) {
            is ListenerWork.Connected -> {
                graph.repository.recordHealth(
                    kind = "LISTENER_CONNECTED",
                    occurredAtEpochMillis = work.observedAtEpochMillis,
                    runtimeSessionId = graph.runtimeSessionId,
                    listenerConnectionId = work.connectionId,
                )
                graph.statusNotificationController.onListenerConnectionChanged(
                    isConnected = true,
                    currentRecordCount = graph.repository.currentItemCount(),
                    latestItem = graph.repository.latestItem(),
                )
            }
            is ListenerWork.Captured -> {
                val captured = work.value
                val label = graph.sourceLabelResolver.resolve(captured.observation.identity.sourcePackage)
                graph.repository.processCaptured(captured, label)?.let(
                    graph.statusNotificationController::onCommitted,
                )
            }
            is ListenerWork.RecoveryCompleted -> graph.repository.recordHealth(
                kind = "RECOVERY_COMPLETED",
                occurredAtEpochMillis = work.observedAtEpochMillis,
                runtimeSessionId = graph.runtimeSessionId,
                listenerConnectionId = work.connectionId,
            )
            is ListenerWork.RecoveryFailed -> graph.repository.recordHealth(
                kind = "RECOVERY_FAILED",
                occurredAtEpochMillis = work.observedAtEpochMillis,
                runtimeSessionId = graph.runtimeSessionId,
                listenerConnectionId = work.connectionId,
            )
            is ListenerWork.Disconnected -> {
                graph.repository.recordHealth(
                    kind = "LISTENER_DISCONNECTED",
                    occurredAtEpochMillis = work.observedAtEpochMillis,
                    runtimeSessionId = graph.runtimeSessionId,
                    listenerConnectionId = work.connectionId,
                )
                graph.statusNotificationController.onListenerConnectionChanged(isConnected = false)
            }
        }
    }

    private sealed interface ListenerWork {
        data class Connected(val connectionId: String, val observedAtEpochMillis: Long) : ListenerWork
        data class Captured(val value: SnapshotResult.Captured) : ListenerWork
        data class RecoveryCompleted(val connectionId: String, val observedAtEpochMillis: Long) : ListenerWork
        data class RecoveryFailed(val connectionId: String, val observedAtEpochMillis: Long) : ListenerWork
        data class Disconnected(val connectionId: String?, val observedAtEpochMillis: Long) : ListenerWork
    }

    private companion object {
        const val WORK_QUEUE_CAPACITY = 256
        const val REBIND_DELAY_MILLIS = 3_000L
    }
}
