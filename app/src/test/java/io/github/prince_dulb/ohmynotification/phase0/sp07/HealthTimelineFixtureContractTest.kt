package io.github.prince_dulb.ohmynotification.phase0.sp07

import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTimelineFixtureContractTest {
    @Test
    fun committedBytesMatchMetadataAndHealthContract() {
        val datasetBytes = resourceBytes("$DATASET_ID/dataset.json")
        val metadata = Properties().apply {
            resource("$DATASET_ID/metadata.properties").use { stream ->
                load(InputStreamReader(stream, Charsets.UTF_8))
            }
        }
        val dataset = datasetBytes.toString(Charsets.UTF_8)

        assertEquals(DATASET_ID, metadata.getProperty("dataset.id"))
        assertEquals(SEED.toString(), metadata.getProperty("seed"))
        assertEquals(metadata.getProperty("sha256"), sha256(datasetBytes))
        assertTrue(dataset.contains("OMN_SYNTHETIC_HEALTH_EVIDENCE"))
        assertTrue(dataset.contains("\"randomCoverageIterations\": 1000"))
        assertTrue(dataset.contains("\"periodicWakeups\": 0"))
        EXPECTED_FACETS.forEach { facet -> assertTrue(dataset.contains("\"$facet\"")) }
        EXPECTED_CASE_IDS.forEach { caseId -> assertTrue(dataset.contains("\"$caseId\"")) }
        FORBIDDEN_REAL_WORLD_MARKERS.forEach { marker ->
            assertFalse("fixture must not contain $marker", dataset.contains(marker, ignoreCase = true))
        }
    }

    private fun resource(path: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
        "Missing test resource: $path"
    }

    private fun resourceBytes(path: String) = resource(path).use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val DATASET_ID = "health-evidence-v1"
        const val SEED = 2026082307L
        val EXPECTED_FACETS = listOf(
            "PROCESS",
            "LISTENER",
            "LISTENER_ACCESS",
            "STATUS_PERMISSION",
            "STATUS_CHANNEL",
        )
        val EXPECTED_CASE_IDS = listOf(
            "current-connection-live-projection",
            "between-adjacent-positive-evidence",
            "explicit-disconnect-boundary",
            "sudden-termination-is-unconfirmed",
            "process-and-access-alone-are-unconfirmed",
            "status-permission-and-channel-are-independent",
            "listener-access-revocation-boundary",
            "same-time-sequence-order",
            "cross-session-connection-reuse",
            "wall-clock-rollback-diagnostic",
            "random-complete-gapless-coverage",
        )
        val FORBIDDEN_REAL_WORLD_MARKERS = listOf(
            "tv.danmaku.bili",
            "bilibili.com",
            "C:\\Users\\",
            "D:\\",
        )
    }
}
