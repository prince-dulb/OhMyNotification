package io.github.prince_dulb.ohmynotification.fixtures

import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFieldsFixtureContractTest {
    @Test
    fun committedBytesMatchMetadataAndPrivacyContract() {
        val datasetBytes = resourceBytes("notification-fields-v1/dataset.json")
        val metadata = Properties().apply {
            resource("notification-fields-v1/metadata.properties").use { stream ->
                load(InputStreamReader(stream, Charsets.UTF_8))
            }
        }
        val dataset = datasetBytes.toString(Charsets.UTF_8)

        assertEquals(DATASET_ID, metadata.getProperty("dataset.id"))
        assertEquals(SEED.toString(), metadata.getProperty("seed"))
        assertEquals(metadata.getProperty("sha256"), sha256(datasetBytes))
        assertEquals(6, Regex("\\\"caseId\\\"").findAll(dataset).count())
        assertTrue(dataset.contains("OMN_SYNTHETIC_"))

        FORBIDDEN_REAL_WORLD_MARKERS.forEach { marker ->
            assertFalse("fixture must not contain $marker", dataset.contains(marker, ignoreCase = true))
        }
    }

    @Test
    fun syntheticIdsAreDeterministicFromTheRecordedSeed() {
        EXPECTED_CASE_IDS.forEach { (caseId, committedId) ->
            assertEquals(committedId, syntheticId(caseId))
        }
    }

    private fun syntheticId(caseId: String): String {
        val input = "$DATASET_ID:$SEED:$caseId".toByteArray(Charsets.UTF_8)
        return sha256(input).take(12)
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
        const val DATASET_ID = "notification-fields-v1"
        const val SEED = 2026082301L

        val EXPECTED_CASE_IDS = mapOf(
            "missing-title" to "87aed4ba6f01",
            "missing-text" to "3213617df6ec",
            "unicode" to "d2e2e0aca3ca",
            "long-text" to "ce232c46591c",
            "invalid-time" to "dedc6af33e29",
            "action" to "f9d9fa9ec799",
        )

        val FORBIDDEN_REAL_WORLD_MARKERS = listOf(
            "tv.danmaku.bili",
            "bilibili.com",
            "C:\\Users\\",
            "D:\\",
        )
    }
}
