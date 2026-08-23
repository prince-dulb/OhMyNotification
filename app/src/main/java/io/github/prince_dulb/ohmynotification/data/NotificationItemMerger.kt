package io.github.prince_dulb.ohmynotification.data

import io.github.prince_dulb.ohmynotification.core.model.NotificationObservation
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizedContent

internal data class NotificationMergeOutcome(
    val item: NotificationItemEntity,
    val isNewItem: Boolean,
)

internal object NotificationItemMerger {
    fun merge(
        previous: NotificationItemEntity?,
        observation: NotificationObservation,
        content: NormalizedContent,
    ): NotificationMergeOutcome {
        val identity = observation.identity
        val isNew = previous == null || previous.isRemoved
        val item = if (isNew) {
            NotificationItemEntity(
                sourcePackage = identity.sourcePackage,
                sourceUserRef = identity.sourceUserRef,
                systemKey = identity.systemKey,
                notificationId = identity.notificationId,
                tag = identity.tag,
                lifecycleGeneration = if (previous == null) 0 else previous.lifecycleGeneration + 1,
                firstReceivedAtEpochMillis = observation.observedAtEpochMillis,
                lastUpdatedAtEpochMillis = observation.observedAtEpochMillis,
                sortTimeEpochMillis = observation.observedAtEpochMillis,
                originalPostTimeEpochMillis = content.originalPostTimeEpochMillis,
                title = content.title,
                body = content.body,
                sourceLabelSnapshot = content.sourceLabelSnapshot,
                contentFingerprint = content.contentFingerprint,
                normalizationWarnings = content.warnings.joinToString(",") { it.name },
                normalizationStrategyVersion = content.strategyVersion,
                titleOriginalCodePoints = content.titleOriginalCodePoints,
                bodyOriginalCodePoints = content.bodyOriginalCodePoints,
                hadContentIntent = observation.actionCapabilities.hasContentIntent,
                contentIntentCreatorPackage = observation.actionCapabilities.contentIntentCreatorPackage,
                notificationActionCount = observation.actionCapabilities.notificationActionCount,
                isRemoved = false,
                removedAtEpochMillis = null,
                lastRemovalReason = null,
                lastCallbackKind = observation.callbackKind.name,
                runtimeSessionIdAtLastCapture = observation.runtimeSessionId,
            )
        } else {
            requireNotNull(previous).copy(
                notificationId = identity.notificationId,
                tag = identity.tag,
                lastUpdatedAtEpochMillis = maxOf(
                    previous.lastUpdatedAtEpochMillis,
                    observation.observedAtEpochMillis,
                ),
                sortTimeEpochMillis = previous.sortTimeEpochMillis,
                originalPostTimeEpochMillis = content.originalPostTimeEpochMillis,
                title = content.title,
                body = content.body,
                sourceLabelSnapshot = content.sourceLabelSnapshot ?: previous.sourceLabelSnapshot,
                contentFingerprint = content.contentFingerprint,
                normalizationWarnings = content.warnings.joinToString(",") { it.name },
                normalizationStrategyVersion = content.strategyVersion,
                titleOriginalCodePoints = content.titleOriginalCodePoints,
                bodyOriginalCodePoints = content.bodyOriginalCodePoints,
                hadContentIntent = observation.actionCapabilities.hasContentIntent,
                contentIntentCreatorPackage = observation.actionCapabilities.contentIntentCreatorPackage,
                notificationActionCount = observation.actionCapabilities.notificationActionCount,
                lastCallbackKind = observation.callbackKind.name,
                runtimeSessionIdAtLastCapture = observation.runtimeSessionId,
            )
        }
        return NotificationMergeOutcome(item, isNew)
    }
}
