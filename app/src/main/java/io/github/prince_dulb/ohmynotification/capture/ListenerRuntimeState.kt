package io.github.prince_dulb.ohmynotification.capture

import android.content.ComponentName
import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object OmnNotificationListenerComponent {
    const val CLASS_NAME =
        "io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerService"

    fun componentName(context: Context): ComponentName = ComponentName(context, CLASS_NAME)
}

object ListenerRuntimeState {
    private val connected = AtomicBoolean(false)
    private val connectionStateObserved = AtomicBoolean(false)
    private val processingOperational = AtomicBoolean(true)
    private val connectionId = AtomicReference<String?>(null)
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun isConnected(): Boolean = connected.get()

    fun connectionId(): String? = connectionId.get()

    fun hasObservedConnectionState(): Boolean = connectionStateObserved.get()

    fun isProcessingOperational(): Boolean = processingOperational.get()

    fun setConnected(value: Boolean, currentConnectionId: String? = null) {
        connectionStateObserved.set(true)
        connectionId.set(if (value) currentConnectionId else null)
        val connectionChanged = connected.getAndSet(value) != value
        val processingChanged = value && !processingOperational.getAndSet(true)
        if (connectionChanged || processingChanged) {
            listeners.forEach { listener -> listener(value) }
        }
    }

    fun setProcessingOperational(value: Boolean): Boolean {
        val changed = processingOperational.getAndSet(value) != value
        if (changed) {
            listeners.forEach { listener -> listener(connected.get()) }
        }
        return changed
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(connected.get())
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }
}
