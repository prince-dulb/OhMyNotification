package io.github.prince_dulb.ohmynotification.phase0

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.service.notification.NotificationListenerService

class PhaseZeroListenerRebindActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationListenerService.requestRebind(
            ComponentName(this, PhaseZeroNotificationListener::class.java),
        )
        finish()
    }
}
