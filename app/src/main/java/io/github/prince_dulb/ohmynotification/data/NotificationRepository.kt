package io.github.prince_dulb.ohmynotification.data

import android.app.PendingIntent
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
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
    val changeKind: NotificationChangeKind,
) {
    val isNewItem: Boolean get() = changeKind == NotificationChangeKind.CREATED
    val updatesStatusSummary: Boolean get() =
        changeKind == NotificationChangeKind.CREATED || changeKind == NotificationChangeKind.UPDATED
}

enum class NotificationChangeKind {
    CREATED,
    UPDATED,
    NO_CONTENT_CHANGE,
    REMOVED,
}

class NotificationRepository(
    private val database: OmnDatabase,
    private val normalizer: NotificationNormalizer,
    private val runtimeActionStore: RuntimeActionStore,
    private val policyStore: MonitoringPolicyStore,
) {
    private val dao = database.omnDao()
    private val positiveHealthEvidenceGate = PositiveHealthEvidenceGate()

    val sourceSummaries: Flow<List<SourceSummaryRow>> = dao.observeSourceSummaries()
    val excludedSources: Flow<List<ExcludedSourceEntity>> = dao.observeExcludedSources()
    val itemCount: Flow<Long> = dao.observeItemCount()

    suspend fun currentItemCount(): Long = dao.itemCount()

    suspend fun latestItem(): NotificationItemEntity? = dao.latestItem()

    fun healthEvidenceSince(startEpochMillis: Long): Flow<List<HealthEvidenceEntity>> =
        dao.observeHealthEvidenceSince(startEpochMillis)

    fun pagedItems(sources: Set<AppUserKey>): Flow<PagingData<NotificationItemEntity>> = Pager(
        config = PagingConfig(
            pageSize = 40,
            prefetchDistance = 12,
            enablePlaceholders = false,
            maxSize = 200,
        ),
        pagingSourceFactory = {
            if (sources.isEmpty()) dao.pageAllItems()
            else dao.pageItemsFromSources(buildPageItemsFromSourcesQuery(sources))
        },
    ).flow

    suspend fun processCaptured(
        captured: SnapshotResult.Captured,
        sourceLabel: String?,
    ): NotificationCommit? {
        val observation = captured.observation
        val transactionResult = database.withTransaction {
            when (observation.callbackKind) {
                ObservedCallbackKind.REMOVED -> commitRemoval(observation)
                ObservedCallbackKind.POST_OR_UPDATE,
                ObservedCallbackKind.RECOVERY_SNAPSHOT,
                -> commitContent(observation, sourceLabel)
            }
        }
        transactionResult.positiveEvidence?.let(positiveHealthEvidenceGate::markRecorded)
        val commit = transactionResult.commit

        if (observation.callbackKind != ObservedCallbackKind.REMOVED && commit != null) {
            runtimeActionStore.put(commit.item.itemId, captured.runtimeAction)
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
        try {
            trimHealthEvidenceIfNeeded(evidenceId)
        } finally {
            if (kind in POSITIVE_HEALTH_KINDS && listenerConnectionId != null) {
                positiveHealthEvidenceGate.markRecorded(
                    PositiveHealthEvidence(
                        runtimeSessionId = runtimeSessionId,
                        listenerConnectionId = listenerConnectionId,
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    ),
                )
            }
        }
    }

    suspend fun reconcilePolicyFromDatabase() {
        policyStore.replaceUserExclusions(dao.excludedPackageNames().toSet())
    }

    suspend fun setSourceExcluded(sourcePackage: String, excluded: Boolean) {
        val alreadyExcluded = sourcePackage in policyStore.snapshot().userExcludedPackages
        if (alreadyExcluded == excluded) return
        if (excluded) {
            dao.insertExcludedSource(
                ExcludedSourceEntity(sourcePackage, System.currentTimeMillis()),
            )
        } else {
            dao.deleteExcludedSource(
                ExcludedSourceEntity(sourcePackage, excludedAtEpochMillis = 0L),
            )
        }
        policyStore.setExcluded(sourcePackage, excluded)
    }

    fun sendRuntimeAction(item: NotificationItemEntity): RuntimeActionStatus =
        runtimeActionStore.sendFromVisibleActivity(item.itemId)

    fun hasRuntimeAction(item: NotificationItemEntity): Boolean = runtimeActionStore.contains(item.itemId)

    private suspend fun commitContent(
        observation: NotificationObservation,
        sourceLabel: String?,
    ): NotificationTransactionResult {
        val normalized = normalizer.normalize(observation, sourceLabel)
        if (normalized !is NormalizationResult.Normalized) return NotificationTransactionResult(null)

        val identity = observation.identity
        val previous = dao.latestForIdentity(
            identity.sourcePackage,
            identity.sourceUserRef,
            identity.systemKey,
        )
        val outcome = NotificationItemMerger.merge(previous, observation, normalized.content)
        val entity = outcome.item
        if (
            !outcome.isNewItem &&
            previous != null &&
            NotificationItemMerger.hasSameCapturedState(previous, entity)
        ) {
            return NotificationTransactionResult(
                NotificationCommit(previous, NotificationChangeKind.NO_CONTENT_CHANGE),
            )
        }
        val itemId = if (outcome.isNewItem) dao.insertItem(entity) else {
            dao.updateItem(entity)
            entity.itemId
        }
        val committed = entity.copy(itemId = itemId)
        val presentationChanged = previous == null ||
            previous.title != committed.title ||
            previous.body != committed.body ||
            previous.sourceLabelSnapshot != committed.sourceLabelSnapshot
        val changeKind = when {
            outcome.isNewItem -> NotificationChangeKind.CREATED
            presentationChanged -> NotificationChangeKind.UPDATED
            else -> NotificationChangeKind.NO_CONTENT_CHANGE
        }
        val positiveEvidence = PositiveHealthEvidence(
            runtimeSessionId = observation.runtimeSessionId,
            listenerConnectionId = observation.listenerConnectionId,
            occurredAtEpochMillis = observation.observedAtEpochMillis,
        )
        val shouldRecordPositiveEvidence = outcome.isNewItem ||
            positiveHealthEvidenceGate.isDue(positiveEvidence, UPDATE_HEALTH_EVIDENCE_WINDOW_MILLIS)
        if (shouldRecordPositiveEvidence) {
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
        }
        return NotificationTransactionResult(
            commit = NotificationCommit(committed, changeKind),
            positiveEvidence = positiveEvidence.takeIf { shouldRecordPositiveEvidence },
        )
    }

    private suspend fun commitRemoval(observation: NotificationObservation): NotificationTransactionResult {
        val identity = observation.identity
        val previous = dao.latestForIdentity(
            identity.sourcePackage,
            identity.sourceUserRef,
            identity.systemKey,
        ) ?: return NotificationTransactionResult(null)
        if (previous.isRemoved) {
            return NotificationTransactionResult(
                NotificationCommit(previous, NotificationChangeKind.NO_CONTENT_CHANGE),
            )
        }

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
        return NotificationTransactionResult(
            commit = NotificationCommit(removed, NotificationChangeKind.REMOVED),
            positiveEvidence = PositiveHealthEvidence(
                runtimeSessionId = observation.runtimeSessionId,
                listenerConnectionId = observation.listenerConnectionId,
                occurredAtEpochMillis = observation.observedAtEpochMillis,
            ),
        )
    }

    private suspend fun trimHealthEvidenceIfNeeded(latestEvidenceId: Long) {
        if (latestEvidenceId % HEALTH_TRIM_INTERVAL == 0L) {
            dao.trimHealthEvidence(HEALTH_EVIDENCE_LIMIT)
        }
    }

    private companion object {
        const val HEALTH_EVIDENCE_LIMIT = 10_000
        const val HEALTH_TRIM_INTERVAL = 256L
        const val UPDATE_HEALTH_EVIDENCE_WINDOW_MILLIS = 5 * 60 * 1_000L
        val POSITIVE_HEALTH_KINDS = setOf(
            "LISTENER_CONNECTED",
            "NOTIFICATION_COMMITTED",
            "NOTIFICATION_REMOVED",
            "RECOVERY_COMPLETED",
        )
    }
}

private data class NotificationTransactionResult(
    val commit: NotificationCommit?,
    val positiveEvidence: PositiveHealthEvidence? = null,
)

internal data class PositiveHealthEvidence(
    val runtimeSessionId: String,
    val listenerConnectionId: String,
    val occurredAtEpochMillis: Long,
)

internal class PositiveHealthEvidenceGate(
    private val maxRememberedConnections: Int = 16,
) {
    private data class ConnectionKey(
        val runtimeSessionId: String,
        val listenerConnectionId: String,
    )

    private val lastRecordedAtByConnection = object : LinkedHashMap<ConnectionKey, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConnectionKey, Long>?): Boolean =
            size > maxRememberedConnections
    }

    init {
        require(maxRememberedConnections > 0)
    }

    @Synchronized
    fun isDue(evidence: PositiveHealthEvidence, minimumIntervalMillis: Long): Boolean {
        require(minimumIntervalMillis >= 0L)
        val lastRecordedAt = lastRecordedAtByConnection[evidence.connectionKey()] ?: return true
        return evidence.occurredAtEpochMillis < lastRecordedAt ||
            evidence.occurredAtEpochMillis - lastRecordedAt >= minimumIntervalMillis
    }

    @Synchronized
    fun markRecorded(evidence: PositiveHealthEvidence) {
        lastRecordedAtByConnection[evidence.connectionKey()] = evidence.occurredAtEpochMillis
    }

    private fun PositiveHealthEvidence.connectionKey() = ConnectionKey(
        runtimeSessionId = runtimeSessionId,
        listenerConnectionId = listenerConnectionId,
    )
}

internal fun buildPageItemsFromSourcesQuery(sources: Set<AppUserKey>): SupportSQLiteQuery {
    require(sources.isNotEmpty())
    val ordered = sources.sortedWith(
        compareBy(AppUserKey::sourcePackage, AppUserKey::sourceUserRef),
    )
    val where = ordered.joinToString(separator = " OR ") {
        "(sourcePackage = ? AND sourceUserRef = ?)"
    }
    val arguments = ordered.flatMap { source ->
        listOf(source.sourcePackage, source.sourceUserRef)
    }.toTypedArray()
    return SimpleSQLiteQuery(
        "SELECT * FROM notification_items WHERE $where " +
            "ORDER BY sortTimeEpochMillis DESC, itemId DESC",
        arguments,
    )
}
