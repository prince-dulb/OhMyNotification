package io.github.prince_dulb.ohmynotification.capture

import android.app.ActivityOptions
import android.app.PendingIntent

enum class RuntimeActionStatus {
    ACCEPTED,
    CANCELED,
    NOT_FOUND,
    SECURITY_REJECTED,
}

class RuntimeActionStore(private val maxHandles: Int = 512) {
    init {
        require(maxHandles > 0)
    }

    private val handles = object : LinkedHashMap<Long, PendingIntent>(maxHandles + 1, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, PendingIntent>?,
        ): Boolean = size > maxHandles
    }

    @Synchronized
    fun put(itemId: Long, action: PendingIntent?) {
        require(itemId > 0L)
        if (action == null) handles.remove(itemId) else handles[itemId] = action
    }

    @Synchronized
    fun contains(itemId: Long): Boolean = itemId in handles

    @Synchronized
    fun sendFromVisibleActivity(itemId: Long): RuntimeActionStatus {
        val action = handles[itemId] ?: return RuntimeActionStatus.NOT_FOUND
        val options = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        }
        return try {
            action.send(options.toBundle())
            RuntimeActionStatus.ACCEPTED
        } catch (_: PendingIntent.CanceledException) {
            handles.remove(itemId)
            RuntimeActionStatus.CANCELED
        } catch (_: SecurityException) {
            RuntimeActionStatus.SECURITY_REJECTED
        }
    }

    @Synchronized
    fun size(): Int = handles.size
}
