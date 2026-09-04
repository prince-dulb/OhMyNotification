package io.github.prince_dulb.ohmynotification.core.health

enum class StatusPresentationState {
    LISTENING,
    PROCESSING_INTERRUPTED,
    WAITING_FOR_CONNECTION,
    LISTENER_INTERRUPTED,
    ACCESS_REQUIRED,
}

object StatusPresentationDeriver {
    fun derive(
        listenerAccessGranted: Boolean,
        connectionObserved: Boolean,
        listenerConnected: Boolean,
        processingOperational: Boolean,
    ): StatusPresentationState = when {
        !listenerAccessGranted -> StatusPresentationState.ACCESS_REQUIRED
        listenerConnected && !processingOperational -> StatusPresentationState.PROCESSING_INTERRUPTED
        listenerConnected -> StatusPresentationState.LISTENING
        connectionObserved -> StatusPresentationState.LISTENER_INTERRUPTED
        else -> StatusPresentationState.WAITING_FOR_CONNECTION
    }
}
