package io.github.prince_dulb.ohmynotification.capture

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class ListenerRebindTrigger { AUTOMATIC, USER }

enum class ListenerRebindResult {
    REQUESTED,
    ALREADY_CONNECTED,
    ACCESS_REQUIRED,
    COOLDOWN_OR_AUTOMATIC_LIMIT,
    PLATFORM_REJECTED,
}

internal class ListenerRebindController(
    private val context: Context,
    private val gate: ListenerRebindGate = ListenerRebindGate(),
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val requestCount = AtomicInteger(0)
    private val lastTrigger = AtomicReference<ListenerRebindTrigger?>(null)
    private val lastResult = AtomicReference<ListenerRebindResult?>(null)

    fun request(trigger: ListenerRebindTrigger): ListenerRebindResult {
        val result = requestInternal(trigger)
        requestCount.incrementAndGet()
        lastTrigger.set(trigger)
        lastResult.set(result)
        return result
    }

    fun debugSnapshot(): ListenerRebindDebugSnapshot = ListenerRebindDebugSnapshot(
        requestCount = requestCount.get(),
        lastTrigger = lastTrigger.get(),
        lastResult = lastResult.get(),
    )

    private fun requestInternal(trigger: ListenerRebindTrigger): ListenerRebindResult {
        if (ListenerRuntimeState.isConnected()) return ListenerRebindResult.ALREADY_CONNECTED
        val component = OmnNotificationListenerComponent.componentName(context)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (!notificationManager.isNotificationListenerAccessGranted(component)) {
            return ListenerRebindResult.ACCESS_REQUIRED
        }
        if (!gate.tryAcquire(trigger, elapsedRealtimeMillis())) {
            return ListenerRebindResult.COOLDOWN_OR_AUTOMATIC_LIMIT
        }
        return try {
            NotificationListenerService.requestRebind(component)
            ListenerRebindResult.REQUESTED
        } catch (_: RuntimeException) {
            ListenerRebindResult.PLATFORM_REJECTED
        }
    }
}

internal data class ListenerRebindDebugSnapshot(
    val requestCount: Int,
    val lastTrigger: ListenerRebindTrigger?,
    val lastResult: ListenerRebindResult?,
)

internal class ListenerRebindGate(
    private val automaticCooldownMillis: Long = 10_000L,
    private val userCooldownMillis: Long = 1_000L,
    private val maximumAutomaticRequests: Int = 3,
) {
    private var lastRequestAtElapsedMillis: Long? = null
    private var lastUserRequestAtElapsedMillis: Long? = null
    private var automaticRequestCount = 0

    init {
        require(automaticCooldownMillis >= 0L)
        require(userCooldownMillis >= 0L)
        require(maximumAutomaticRequests >= 0)
    }

    @Synchronized
    fun tryAcquire(trigger: ListenerRebindTrigger, nowElapsedMillis: Long): Boolean {
        when (trigger) {
            ListenerRebindTrigger.AUTOMATIC -> {
                if (
                    isWithinCooldown(
                        lastRequestAtElapsedMillis,
                        nowElapsedMillis,
                        automaticCooldownMillis,
                    ) || automaticRequestCount >= maximumAutomaticRequests
                ) {
                    return false
                }
                automaticRequestCount += 1
            }
            ListenerRebindTrigger.USER -> {
                if (
                    isWithinCooldown(
                        lastUserRequestAtElapsedMillis,
                        nowElapsedMillis,
                        userCooldownMillis,
                    )
                ) {
                    return false
                }
                lastUserRequestAtElapsedMillis = nowElapsedMillis
            }
        }
        lastRequestAtElapsedMillis = nowElapsedMillis
        return true
    }

    private fun isWithinCooldown(last: Long?, now: Long, cooldownMillis: Long): Boolean =
        last != null && now - last in 0 until cooldownMillis
}
