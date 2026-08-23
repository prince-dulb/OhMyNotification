package io.github.prince_dulb.ohmynotification.capture

import android.app.ActivityOptions
import android.app.PendingIntent

enum class RuntimeActionStatus {
    ACCEPTED,
    CANCELED,
    NOT_FOUND,
    SECURITY_REJECTED,
}

data class RuntimeActionKey(
    val runtimeSessionId: String,
    val systemKey: String,
)

class RuntimeActionStore(private val maxHandles: Int = 512) {
    init {
        require(maxHandles > 0)
    }

    private val handles = object : LinkedHashMap<RuntimeActionKey, PendingIntent>(maxHandles + 1, 0.75f, false) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<RuntimeActionKey, PendingIntent>?,
        ): Boolean = size > maxHandles
    }

    @Synchronized
    fun put(key: RuntimeActionKey, action: PendingIntent?) {
        if (action != null) handles[key] = action
    }

    @Synchronized
    fun remove(key: RuntimeActionKey) {
        handles.remove(key)
    }

    @Synchronized
    fun contains(key: RuntimeActionKey): Boolean = key in handles

    @Synchronized
    fun sendFromVisibleActivity(key: RuntimeActionKey): RuntimeActionStatus {
        val action = handles[key] ?: return RuntimeActionStatus.NOT_FOUND
        val options = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        }
        return try {
            action.send(options.toBundle())
            RuntimeActionStatus.ACCEPTED
        } catch (_: PendingIntent.CanceledException) {
            handles.remove(key)
            RuntimeActionStatus.CANCELED
        } catch (_: SecurityException) {
            RuntimeActionStatus.SECURITY_REJECTED
        }
    }

    @Synchronized
    fun size(): Int = handles.size
}
