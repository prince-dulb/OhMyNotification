package io.github.prince_dulb.ohmynotification.core.health

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusPresentationTest {
    @Test
    fun accessRequirementHasPriorityOverConnectionMemory() {
        assertEquals(
            StatusPresentationState.ACCESS_REQUIRED,
            StatusPresentationDeriver.derive(
                listenerAccessGranted = false,
                connectionObserved = true,
                listenerConnected = false,
            ),
        )
    }

    @Test
    fun grantedAccessWithoutConnectionEvidenceWaitsHonestly() {
        assertEquals(
            StatusPresentationState.WAITING_FOR_CONNECTION,
            StatusPresentationDeriver.derive(
                listenerAccessGranted = true,
                connectionObserved = false,
                listenerConnected = false,
            ),
        )
    }

    @Test
    fun observedDisconnectIsNotReportedAsNeverConnected() {
        assertEquals(
            StatusPresentationState.LISTENER_INTERRUPTED,
            StatusPresentationDeriver.derive(
                listenerAccessGranted = true,
                connectionObserved = true,
                listenerConnected = false,
            ),
        )
    }

    @Test
    fun liveConnectionReportsListening() {
        assertEquals(
            StatusPresentationState.LISTENING,
            StatusPresentationDeriver.derive(
                listenerAccessGranted = true,
                connectionObserved = true,
                listenerConnected = true,
            ),
        )
    }
}
