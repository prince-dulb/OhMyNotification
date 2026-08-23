package io.github.prince_dulb.ohmynotification.phase0.sp08

import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineQueryFixtureContractTest {
    @Test
    fun committedBytesMatchMetadataAndWorkloadContract() {
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
        assertTrue(dataset.contains("OMN_SYNTHETIC_TIMELINE_QUERY"))
        assertTrue(dataset.contains("\"fullHistoryCount\": 10000"))
        assertTrue(dataset.contains("\"windowMillis\": 900000"))
        assertTrue(dataset.contains("\"maxInterveningOtherItems\": 3"))
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
        const val DATASET_ID = "timeline-query-v1"
        const val SEED = 2026082308L
        val EXPECTED_CASE_IDS = listOf(
            "same-time-sequence-and-id-tie",
            "short-a-b-a",
            "long-a-b-a",
            "exact-fifteen-minutes-and-three-other-items",
            "fourth-other-item-stops-scan",
            "android-user-isolation",
            "consumed-other-item-still-counts",
            "source-filter-before-grouping",
            "page-size-invariance",
            "single-source-extreme-burst",
        )
        val FORBIDDEN_REAL_WORLD_MARKERS = listOf(
            "tv.danmaku.bili",
            "bilibili.com",
            "C:\\Users\\",
            "D:\\",
        )
    }
}
