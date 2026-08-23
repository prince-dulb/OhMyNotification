package io.github.prince_dulb.ohmynotification.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_items",
    indices = [
        Index(value = ["sortTimeEpochMillis", "itemId"]),
        Index(value = ["sourcePackage", "sortTimeEpochMillis", "itemId"]),
        Index(
            value = ["sourcePackage", "sourceUserRef", "systemKey", "lifecycleGeneration"],
            unique = true,
        ),
    ],
)
data class NotificationItemEntity(
    @PrimaryKey(autoGenerate = true)
    val itemId: Long = 0,
    val sourcePackage: String,
    val sourceUserRef: String,
    val systemKey: String,
    val notificationId: Int,
    val tag: String?,
    val lifecycleGeneration: Int,
    val firstReceivedAtEpochMillis: Long,
    val lastUpdatedAtEpochMillis: Long,
    val sortTimeEpochMillis: Long,
    val originalPostTimeEpochMillis: Long?,
    val title: String?,
    val body: String?,
    val sourceLabelSnapshot: String?,
    val contentFingerprint: String?,
    val normalizationWarnings: String,
    val normalizationStrategyVersion: Int,
    val titleOriginalCodePoints: Int,
    val bodyOriginalCodePoints: Int,
    val hadContentIntent: Boolean,
    val contentIntentCreatorPackage: String?,
    val notificationActionCount: Int,
    val isRemoved: Boolean,
    val removedAtEpochMillis: Long?,
    val lastRemovalReason: Int?,
    val lastCallbackKind: String,
    val runtimeSessionIdAtLastCapture: String,
)

@Entity(
    tableName = "health_evidence",
    indices = [Index(value = ["occurredAtEpochMillis", "evidenceId"])],
)
data class HealthEvidenceEntity(
    @PrimaryKey(autoGenerate = true)
    val evidenceId: Long = 0,
    val kind: String,
    val occurredAtEpochMillis: Long,
    val runtimeSessionId: String,
    val listenerConnectionId: String?,
    val itemId: Long?,
)

@Entity(tableName = "excluded_sources")
data class ExcludedSourceEntity(
    @PrimaryKey
    val sourcePackage: String,
    val excludedAtEpochMillis: Long,
)

data class SourceSummaryRow(
    val sourcePackage: String,
    val sourceLabelSnapshot: String?,
    val recordCount: Long,
    val latestTimeEpochMillis: Long,
)
