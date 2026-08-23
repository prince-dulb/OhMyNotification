package io.github.prince_dulb.ohmynotification.core.normalize

import io.github.prince_dulb.ohmynotification.core.model.NotificationObservation
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class NormalizationLimits(
    val maxTitleCodePoints: Int = 2_048,
    val maxBodyCodePoints: Int = 16_384,
    val maxSourceLabelCodePoints: Int = 256,
    val minimumAcceptedPostTimeEpochMillis: Long = 946_684_800_000L,
    val maxFuturePostTimeSkewMillis: Long = 300_000L,
)

enum class NormalizationWarning {
    TITLE_MISSING,
    BODY_MISSING,
    BODY_DUPLICATES_TITLE,
    TITLE_TRUNCATED,
    BODY_TRUNCATED,
    SOURCE_LABEL_TRUNCATED,
    SOURCE_TEXT_COPY_FAILED,
    UNSAFE_CONTROL_REPLACED,
    POST_TIME_REJECTED,
}

data class NormalizedContent(
    val title: String?,
    val body: String?,
    val sourceLabelSnapshot: String?,
    val originalPostTimeEpochMillis: Long?,
    val contentFingerprint: String?,
    val warnings: Set<NormalizationWarning>,
    val strategyVersion: Int,
    val titleOriginalCodePoints: Int,
    val bodyOriginalCodePoints: Int,
)

sealed interface NormalizationResult {
    data class Normalized(val content: NormalizedContent) : NormalizationResult
    data object RemovalHasNoContent : NormalizationResult
}

fun interface ContentFingerprinter {
    fun fingerprint(strategyVersion: Int, title: String?, body: String?): String
}

class HmacSha256Fingerprinter(private val key: ByteArray) : ContentFingerprinter {
    init {
        require(key.size >= 32) { "Fingerprint key must contain at least 32 bytes." }
    }

    override fun fingerprint(strategyVersion: Int, title: String?, body: String?): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(strategyVersion).array())
        updateLengthPrefixed(mac, title)
        updateLengthPrefixed(mac, body)
        return mac.doFinal().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun updateLengthPrefixed(mac: Mac, value: String?) {
        val bytes = value?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        mac.update(bytes)
    }
}

class NotificationNormalizer(
    private val limits: NormalizationLimits,
    private val fingerprinter: ContentFingerprinter,
    private val strategyVersion: Int = 1,
) {
    fun normalize(
        observation: NotificationObservation,
        sourceLabelCandidate: String?,
    ): NormalizationResult {
        if (observation.callbackKind == ObservedCallbackKind.REMOVED) {
            return NormalizationResult.RemovalHasNoContent
        }

        val warnings = linkedSetOf<NormalizationWarning>()
        if (observation.copyWarnings.isNotEmpty()) {
            warnings += NormalizationWarning.SOURCE_TEXT_COPY_FAILED
        }

        val titleResult = normalizeText(observation.content.title, limits.maxTitleCodePoints)
        if (titleResult.controlReplaced) warnings += NormalizationWarning.UNSAFE_CONTROL_REPLACED
        if (titleResult.truncated) warnings += NormalizationWarning.TITLE_TRUNCATED
        if (titleResult.value == null) warnings += NormalizationWarning.TITLE_MISSING

        val bodyResult = listOf(
            observation.content.bigText,
            observation.content.text,
            observation.content.summaryText,
            observation.content.subText,
            observation.content.infoText,
        ).asSequence()
            .map { candidate -> normalizeText(candidate, limits.maxBodyCodePoints) }
            .firstOrNull { result -> result.value != null }
            ?: TextResult.empty()
        if (bodyResult.controlReplaced) warnings += NormalizationWarning.UNSAFE_CONTROL_REPLACED
        if (bodyResult.truncated) warnings += NormalizationWarning.BODY_TRUNCATED

        var body = bodyResult.value
        if (body == null) {
            warnings += NormalizationWarning.BODY_MISSING
        } else if (body == titleResult.value) {
            body = null
            warnings += NormalizationWarning.BODY_DUPLICATES_TITLE
        }

        val sourceLabel = normalizeText(sourceLabelCandidate, limits.maxSourceLabelCodePoints)
        if (sourceLabel.controlReplaced) warnings += NormalizationWarning.UNSAFE_CONTROL_REPLACED
        if (sourceLabel.truncated) warnings += NormalizationWarning.SOURCE_LABEL_TRUNCATED

        val acceptedPostTime = observation.postTimeEpochMillis.takeIf { postTime ->
            postTime >= limits.minimumAcceptedPostTimeEpochMillis &&
                postTime <= observation.observedAtEpochMillis + limits.maxFuturePostTimeSkewMillis
        }
        if (acceptedPostTime == null) warnings += NormalizationWarning.POST_TIME_REJECTED

        val fingerprint = if (titleResult.value == null && body == null) {
            null
        } else {
            fingerprinter.fingerprint(strategyVersion, titleResult.value, body)
        }

        return NormalizationResult.Normalized(
            NormalizedContent(
                title = titleResult.value,
                body = body,
                sourceLabelSnapshot = sourceLabel.value,
                originalPostTimeEpochMillis = acceptedPostTime,
                contentFingerprint = fingerprint,
                warnings = warnings,
                strategyVersion = strategyVersion,
                titleOriginalCodePoints = titleResult.originalCodePoints,
                bodyOriginalCodePoints = bodyResult.originalCodePoints,
            ),
        )
    }

    private fun normalizeText(value: String?, maxCodePoints: Int): TextResult {
        if (value == null) return TextResult.empty()
        require(maxCodePoints > 0)
        val normalized = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                index += Character.charCount(codePoint)
                when {
                    codePoint == '\r'.code -> {
                        if (index < value.length && value[index] == '\n') index += 1
                        append('\n')
                    }
                    codePoint == '\t'.code -> append(' ')
                    codePoint == '\n'.code -> append('\n')
                    codePoint in 0x00..0x1F || codePoint == 0x7F -> append('\uFFFD')
                    else -> appendCodePoint(codePoint)
                }
            }
        }.trim()
        if (normalized.isEmpty()) return TextResult.empty()

        val originalCodePoints = normalized.codePointCount(0, normalized.length)
        val truncated = originalCodePoints > maxCodePoints
        val output = if (truncated) {
            normalized.substring(0, normalized.offsetByCodePoints(0, maxCodePoints))
        } else {
            normalized
        }
        return TextResult(
            value = output,
            originalCodePoints = originalCodePoints,
            truncated = truncated,
            controlReplaced = output.indexOf('\uFFFD') >= 0,
        )
    }

    private data class TextResult(
        val value: String?,
        val originalCodePoints: Int,
        val truncated: Boolean,
        val controlReplaced: Boolean,
    ) {
        companion object {
            fun empty() = TextResult(null, 0, truncated = false, controlReplaced = false)
        }
    }
}
