package io.github.prince_dulb.ohmynotification.phase0

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

class PhaseZeroListenerRebindActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = ComponentName(this, PhaseZeroNotificationListener::class.java)
        if (intent.getBooleanExtra(EXTRA_RESET_BINDING, false)) {
            NotificationListenerService.requestUnbind(component)
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    NotificationListenerService.requestRebind(component)
                    finish()
                },
                REBIND_DELAY_MILLIS,
            )
        } else {
            NotificationListenerService.requestRebind(component)
            finish()
        }
    }

    private companion object {
        const val EXTRA_RESET_BINDING = "omn_reset_binding"
        const val REBIND_DELAY_MILLIS = 250L
    }
}
