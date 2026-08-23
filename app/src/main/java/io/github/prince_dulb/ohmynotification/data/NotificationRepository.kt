package io.github.prince_dulb.ohmynotification.data

import android.app.PendingIntent
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionKey
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStatus
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStore
import io.github.prince_dulb.ohmynotification.capture.SnapshotResult
import io.github.prince_dulb.ohmynotification.core.model.NotificationObservation
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizationResult
import io.github.prince_dulb.ohmynotification.core.normalize.NotificationNormalizer
import kotlinx.coroutines.flow.Flow

data class NotificationCommit(
    val item: NotificationItemEntity,
    val isNewItem: Boolean,
)

class NotificationRepository(
    private val database: OmnDatabase,
    private val normalizer: NotificationNormalizer,
    private val runtimeActionStore: RuntimeActionStore,
    private val policyStore: MonitoringPolicyStore,
) {
    private val dao = database.omnDao()

    val sourceSummaries: Flow<List<SourceSummaryRow>> = dao.observeSourceSummaries()
    val excludedSources: Flow<List<ExcludedSourceEntity>> = dao.observeExcludedSources()
    val itemCount: Flow<Long> = dao.observeItemCount()

    suspend fun currentItemCount(): Long = dao.itemCount()

    fun pagedItems(sourcePackages: Set<String>): Flow<PagingData<NotificationItemEntity>> = Pager(
        config = PagingConfig(pageSize = 40, prefetchDistance = 12, enablePlaceholders = false),
        pagingSourceFactory = {
            if (sourcePackages.isEmpty()) dao.pageAllItems()
            else dao.pageItemsFromSources(sourcePackages.sorted())
        },
    ).flow

    suspend fun processCaptured(
        captured: SnapshotResult.Captured,
        sourceLabel: String?,
    ): NotificationCommit? {
        val observation = captured.observation
        val commit = database.withTransaction {
            when (observation.callbackKind) {
                ObservedCallbackKind.REMOVED -> commitRemoval(observation)
                ObservedCallbackKind.POST_OR_UPDATE,
                ObservedCallbackKind.RECOVERY_SNAPSHOT,
                -> commitContent(observation, sourceLabel)
            }
        }

        val key = RuntimeActionKey(observation.runtimeSessionId, observation.identity.systemKey)
        if (observation.callbackKind != ObservedCallbackKind.REMOVED && commit != null) {
            runtimeActionStore.put(key, captured.runtimeAction)
        }
        return commit
    }

    suspend fun recordHealth(
        kind: String,
        occurredAtEpochMillis: Long,
        runtimeSessionId: String,
        listenerConnectionId: String?,
    ) {
        val evidenceId = dao.insertHealthEvidence(
            HealthEvidenceEntity(
                kind = kind,
                occurredAtEpochMillis = occurredAtEpochMillis,
                runtimeSessionId = runtimeSessionId,
                listenerConnectionId = listenerConnectionId,
                itemId = null,
            ),
        )
        trimHealthEvidenceIfNeeded(evidenceId)
    }

    suspend fun reconcilePolicyFromDatabase() {
        policyStore.replaceUserExclusions(dao.excludedPackageNames().toSet())
    }

    suspend fun setSourceExcluded(sourcePackage: String, excluded: Boolean) {
        if (excluded) {
            policyStore.setExcluded(sourcePackage, true)
            dao.insertExcludedSource(
                ExcludedSourceEntity(sourcePackage, System.currentTimeMillis()),
            )
        } else {
            dao.deleteExcludedSource(
                ExcludedSourceEntity(sourcePackage, excludedAtEpochMillis = 0L),
            )
            policyStore.setExcluded(sourcePackage, false)
        }
    }

    fun sendRuntimeAction(item: NotificationItemEntity): RuntimeActionStatus =
        runtimeActionStore.sendFromVisibleActivity(
            RuntimeActionKey(item.runtimeSessionIdAtLastCapture, item.systemKey),
        )

    private suspend fun commitContent(
        observation: NotificationObservation,
        sourceLabel: String?,
    ): NotificationCommit? {
        val normalized = normalizer.normalize(observation, sourceLabel)
        if (normalized !is NormalizationResult.Normalized) return null

        val identity = observation.identity
        val previous = dao.latestForIdentity(
            identity.sourcePackage,
            identity.sourceUserRef,
            identity.systemKey,
        )
        val content = normalized.content
        val displayTime = content.originalPostTimeEpochMillis ?: observation.observedAtEpochMillis
        val isNew = previous == null || previous.isRemoved
        val entity = if (isNew) {
            NotificationItemEntity(
                sourcePackage = identity.sourcePackage,
                sourceUserRef = identity.sourceUserRef,
                systemKey = identity.systemKey,
                notificationId = identity.notificationId,
                tag = identity.tag,
                lifecycleGeneration = if (previous == null) 0 else previous.lifecycleGeneration + 1,
                firstReceivedAtEpochMillis = observation.observedAtEpochMillis,
                lastUpdatedAtEpochMillis = observation.observedAtEpochMillis,
                sortTimeEpochMillis = displayTime,
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
                sortTimeEpochMillis = maxOf(previous.sortTimeEpochMillis, displayTime),
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
        val itemId = if (isNew) dao.insertItem(entity) else {
            dao.updateItem(entity)
            entity.itemId
        }
        val committed = entity.copy(itemId = itemId)
        val evidenceId = dao.insertHealthEvidence(
            HealthEvidenceEntity(
                kind = "NOTIFICATION_COMMITTED",
                occurredAtEpochMillis = observation.observedAtEpochMillis,
                runtimeSessionId = observation.runtimeSessionId,
                listenerConnectionId = observation.listenerConnectionId,
                itemId = itemId,
            ),
        )
        trimHealthEvidenceIfNeeded(evidenceId)
        return NotificationCommit(committed, isNew)
    }

    private suspend fun commitRemoval(observation: NotificationObservation): NotificationCommit? {
        val identity = observation.identity
        val previous = dao.latestForIdentity(
            identity.sourcePackage,
            identity.sourceUserRef,
            identity.systemKey,
        ) ?: return null
        if (previous.isRemoved) return NotificationCommit(previous, isNewItem = false)

        val removed = previous.copy(
            lastUpdatedAtEpochMillis = maxOf(
                previous.lastUpdatedAtEpochMillis,
                observation.observedAtEpochMillis,
            ),
            isRemoved = true,
            removedAtEpochMillis = observation.observedAtEpochMillis,
            lastRemovalReason = observation.removalReason,
            lastCallbackKind = observation.callbackKind.name,
            runtimeSessionIdAtLastCapture = observation.runtimeSessionId,
        )
        dao.updateItem(removed)
        val evidenceId = dao.insertHealthEvidence(
            HealthEvidenceEntity(
                kind = "NOTIFICATION_REMOVED",
                occurredAtEpochMillis = observation.observedAtEpochMillis,
                runtimeSessionId = observation.runtimeSessionId,
                listenerConnectionId = observation.listenerConnectionId,
                itemId = removed.itemId,
            ),
        )
        trimHealthEvidenceIfNeeded(evidenceId)
        return NotificationCommit(removed, isNewItem = false)
    }

    private suspend fun trimHealthEvidenceIfNeeded(latestEvidenceId: Long) {
        if (latestEvidenceId % HEALTH_TRIM_INTERVAL == 0L) {
            dao.trimHealthEvidence(HEALTH_EVIDENCE_LIMIT)
        }
    }

    private companion object {
        const val HEALTH_EVIDENCE_LIMIT = 10_000
        const val HEALTH_TRIM_INTERVAL = 256L
    }
}
