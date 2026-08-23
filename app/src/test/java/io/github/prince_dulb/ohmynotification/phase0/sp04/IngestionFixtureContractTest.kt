package io.github.prince_dulb.ohmynotification.phase0.sp04

import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionFixtureContractTest {
    @Test
    fun committedBytesMatchMetadataAndBurstContract() {
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
        assertTrue(dataset.contains("OMN_SYNTHETIC_INGESTION_EVENTS"))
        assertTrue(dataset.contains("\"totalCallbacks\": 100"))
        assertTrue(dataset.contains("\"expectedItems\": 25"))
        assertTrue(dataset.contains("\"expectedEvents\": 100"))
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
        const val DATASET_ID = "ingestion-events-v1"
        const val SEED = 2026082304L
        val EXPECTED_CASE_IDS = listOf(
            "exact-replay",
            "recovery-match",
            "content-update-keeps-first-position",
            "persistent-target-only-update",
            "same-text-different-system-key",
            "same-system-id-different-user",
            "remove-and-repost-next-generation",
            "orphan-removal",
            "identity-evidence-conflict",
            "wall-clock-jump-keeps-ingest-order",
            "fault-after-sequence-allocation",
            "fault-after-item-write",
            "fault-after-event-write",
            "fault-after-health-write",
            "explicit-backpressure-accounting",
        )
        val FORBIDDEN_REAL_WORLD_MARKERS = listOf(
            "tv.danmaku.bili",
            "bilibili.com",
            "C:\\Users\\",
            "D:\\",
        )
    }
}
