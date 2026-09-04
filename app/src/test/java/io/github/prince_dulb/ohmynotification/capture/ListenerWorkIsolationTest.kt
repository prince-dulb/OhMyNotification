package io.github.prince_dulb.ohmynotification.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerWorkIsolationTest {
    @Test
    fun failedWorkIsReportedAndNextWorkCanStillRun() = runBlocking {
        val failures = mutableListOf<String>()
        var completed = 0

        val failed = isolateListenerWork(
            block = { error("broken work") },
            onFailure = { failure -> failures += failure.message.orEmpty() },
        )
        val recovered = isolateListenerWork(
            block = { completed += 1 },
            onFailure = { failure -> failures += failure.message.orEmpty() },
        )

        assertFalse(failed)
        assertTrue(recovered)
        assertEquals(listOf("broken work"), failures)
        assertEquals(1, completed)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoAWorkFailure() {
        runBlocking {
            isolateListenerWork(
                block = { throw CancellationException("scope stopped") },
                onFailure = { error("cancellation must not be reported as a work failure") },
            )
        }
    }

    @Test
    fun failureReporterCannotKillTheConsumer() = runBlocking {
        val result = isolateListenerWork(
            block = { error("broken work") },
            onFailure = { error("broken reporter") },
        )

        assertFalse(result)
    }
}
