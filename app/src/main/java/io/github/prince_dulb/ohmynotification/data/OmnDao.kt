package io.github.prince_dulb.ohmynotification.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OmnDao {
    @Query(
        """
        SELECT * FROM notification_items
        ORDER BY sortTimeEpochMillis DESC, itemId DESC
        """,
    )
    fun pageAllItems(): PagingSource<Int, NotificationItemEntity>

    @Query(
        """
        SELECT * FROM notification_items
        WHERE sourcePackage IN (:sourcePackages)
        ORDER BY sortTimeEpochMillis DESC, itemId DESC
        """,
    )
    fun pageItemsFromSources(sourcePackages: List<String>): PagingSource<Int, NotificationItemEntity>

    @Query(
        """
        SELECT * FROM notification_items
        WHERE sourcePackage = :sourcePackage
          AND sourceUserRef = :sourceUserRef
          AND systemKey = :systemKey
        ORDER BY lifecycleGeneration DESC
        LIMIT 1
        """,
    )
    suspend fun latestForIdentity(
        sourcePackage: String,
        sourceUserRef: String,
        systemKey: String,
    ): NotificationItemEntity?

    @Insert
    suspend fun insertItem(item: NotificationItemEntity): Long

    @Update
    suspend fun updateItem(item: NotificationItemEntity)

    @Insert
    suspend fun insertHealthEvidence(evidence: HealthEvidenceEntity): Long

    @Query(
        """
        DELETE FROM health_evidence
        WHERE evidenceId NOT IN (
            SELECT evidenceId FROM health_evidence
            ORDER BY evidenceId DESC
            LIMIT :keepCount
        )
        """,
    )
    suspend fun trimHealthEvidence(keepCount: Int)

    @Query(
        """
        SELECT sourcePackage,
               MAX(sourceLabelSnapshot) AS sourceLabelSnapshot,
               COUNT(*) AS recordCount,
               MAX(sortTimeEpochMillis) AS latestTimeEpochMillis
        FROM notification_items
        GROUP BY sourcePackage
        ORDER BY latestTimeEpochMillis DESC, sourcePackage ASC
        """,
    )
    fun observeSourceSummaries(): Flow<List<SourceSummaryRow>>

    @Query("SELECT COUNT(*) FROM notification_items")
    fun observeItemCount(): Flow<Long>

    @Query("SELECT COUNT(*) FROM notification_items")
    suspend fun itemCount(): Long

    @Query("SELECT * FROM excluded_sources ORDER BY sourcePackage ASC")
    fun observeExcludedSources(): Flow<List<ExcludedSourceEntity>>

    @Query("SELECT sourcePackage FROM excluded_sources")
    suspend fun excludedPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExcludedSource(source: ExcludedSourceEntity)

    @Delete
    suspend fun deleteExcludedSource(source: ExcludedSourceEntity)
}
