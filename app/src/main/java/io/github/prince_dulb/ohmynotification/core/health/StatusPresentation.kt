package io.github.prince_dulb.ohmynotification.core.health

enum class StatusPresentationState {
    LISTENING,
    WAITING_FOR_CONNECTION,
    LISTENER_INTERRUPTED,
    ACCESS_REQUIRED,
}

object StatusPresentationDeriver {
    fun derive(
        listenerAccessGranted: Boolean,
        connectionObserved: Boolean,
        listenerConnected: Boolean,
    ): StatusPresentationState = when {
        !listenerAccessGranted -> StatusPresentationState.ACCESS_REQUIRED
        listenerConnected -> StatusPresentationState.LISTENING
        connectionObserved -> StatusPresentationState.LISTENER_INTERRUPTED
        else -> StatusPresentationState.WAITING_FOR_CONNECTION
    }
}
