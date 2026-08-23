package io.github.prince_dulb.ohmynotification.core.normalize

import io.github.prince_dulb.ohmynotification.core.model.ActionCapabilitySet
import io.github.prince_dulb.ohmynotification.core.model.NotificationIdentity
import io.github.prince_dulb.ohmynotification.core.model.NotificationObservation
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import io.github.prince_dulb.ohmynotification.core.model.RawVisibleContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationNormalizerTest {
    private val normalizer = NotificationNormalizer(
        limits = NormalizationLimits(maxTitleCodePoints = 4, maxBodyCodePoints = 8),
        fingerprinter = HmacSha256Fingerprinter(ByteArray(32) { it.toByte() }),
    )

    @Test
    fun `prefers big text and normalizes controls without splitting emoji`() {
        val result = normalizer.normalize(
            observation = observation(
                title = " 题\t目 ",
                text = "short",
                bigText = "A\r\nB\u0000😀😀😀",
            ),
            sourceLabelCandidate = "Bilibili",
        ) as NormalizationResult.Normalized

        assertEquals("题 目", result.content.title)
        assertEquals("A\nB�😀😀😀", result.content.body)
        assertTrue(NormalizationWarning.UNSAFE_CONTROL_REPLACED in result.content.warnings)
        assertFalse(result.content.body!!.last().isHighSurrogate())
    }

    @Test
    fun `reports transparent truncation and stable fingerprint`() {
        val input = observation(title = "12345", text = "abcdefghijk")
        val first = normalizer.normalize(input, null) as NormalizationResult.Normalized
        val second = normalizer.normalize(input, null) as NormalizationResult.Normalized

        assertEquals("1234", first.content.title)
        assertEquals("abcdefgh", first.content.body)
        assertEquals(5, first.content.titleOriginalCodePoints)
        assertEquals(11, first.content.bodyOriginalCodePoints)
        assertTrue(NormalizationWarning.TITLE_TRUNCATED in first.content.warnings)
        assertTrue(NormalizationWarning.BODY_TRUNCATED in first.content.warnings)
        assertEquals(first.content.contentFingerprint, second.content.contentFingerprint)
    }

    @Test
    fun `keeps missing fields null and removes duplicate body`() {
        val missing = normalizer.normalize(
            observation = observation(title = null, text = " "),
            sourceLabelCandidate = null,
        ) as NormalizationResult.Normalized
        assertNull(missing.content.title)
        assertNull(missing.content.body)
        assertNull(missing.content.contentFingerprint)
        assertTrue(NormalizationWarning.TITLE_MISSING in missing.content.warnings)
        assertTrue(NormalizationWarning.BODY_MISSING in missing.content.warnings)

        val duplicate = normalizer.normalize(
            observation = observation(title = "same", text = "same"),
            sourceLabelCandidate = null,
        ) as NormalizationResult.Normalized
        assertNull(duplicate.content.body)
        assertTrue(NormalizationWarning.BODY_DUPLICATES_TITLE in duplicate.content.warnings)
    }

    @Test
    fun `removal skips content normalization and bad post time stays null`() {
        assertEquals(
            NormalizationResult.RemovalHasNoContent,
            normalizer.normalize(
                observation = observation(
                    title = "ignored",
                    text = "ignored",
                    callbackKind = ObservedCallbackKind.REMOVED,
                ),
                sourceLabelCandidate = null,
            ),
        )

        val invalidTime = normalizer.normalize(
            observation = observation(title = "title", text = "body", postTime = -1L),
            sourceLabelCandidate = null,
        ) as NormalizationResult.Normalized
        assertNull(invalidTime.content.originalPostTimeEpochMillis)
        assertTrue(NormalizationWarning.POST_TIME_REJECTED in invalidTime.content.warnings)
    }

    private fun observation(
        title: String?,
        text: String?,
        bigText: String? = null,
        callbackKind: ObservedCallbackKind = ObservedCallbackKind.POST_OR_UPDATE,
        postTime: Long = 1_700_000_000_000L,
    ) = NotificationObservation(
        runtimeSessionId = "runtime",
        listenerConnectionId = "connection",
        callbackKind = callbackKind,
        identity = NotificationIdentity("source.package", "UserHandle{0}", "key", 1, null),
        postTimeEpochMillis = postTime,
        observedAtEpochMillis = 1_700_000_000_000L,
        observedElapsedNanos = 1L,
        content = RawVisibleContent(
            title = title,
            text = text,
            bigText = bigText,
            subText = null,
            summaryText = null,
            infoText = null,
            category = null,
            channelId = null,
            groupKey = null,
        ),
        copyWarnings = emptySet(),
        actionCapabilities = ActionCapabilitySet(false, null, 0, false),
        policyRevision = 0,
        removalReason = null,
    )
}
