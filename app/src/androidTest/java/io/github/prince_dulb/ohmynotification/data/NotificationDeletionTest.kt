package io.github.prince_dulb.ohmynotification.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationDeletionTest {
    @Test
    fun deletingOneItemKeepsOtherItemsAndHealthEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OmnDatabase::class.java).build()
        try {
            val dao = database.omnDao()
            val deletedItemId = dao.insertItem(item("delete", 200L))
            val keptItemId = dao.insertItem(item("keep", 100L))
            dao.insertHealthEvidence(healthEvidence(deletedItemId, 201L))
            dao.insertHealthEvidence(healthEvidence(keptItemId, 101L))

            val deleted = database.withTransaction { dao.deleteItem(deletedItemId) == 1 }

            assertEquals(true, deleted)
            assertEquals(1, dao.healthEvidenceCountForItem(deletedItemId))
            assertEquals(1, dao.healthEvidenceCountForItem(keptItemId))
            assertEquals(listOf(keptItemId), loadAll(dao).map(NotificationItemEntity::itemId))
        } finally {
            database.close()
        }
    }

    private suspend fun loadAll(dao: OmnDao): List<NotificationItemEntity> {
        val result = dao.pageAllItems().load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false,
            ),
        )
        return (result as PagingSource.LoadResult.Page).data
    }

    private fun item(key: String, time: Long) = NotificationItemEntity(
        sourcePackage = "example.delete",
        sourceUserRef = "user",
        systemKey = key,
        notificationId = time.toInt(),
        tag = null,
        lifecycleGeneration = 0,
        firstReceivedAtEpochMillis = time,
        lastUpdatedAtEpochMillis = time,
        sortTimeEpochMillis = time,
        originalPostTimeEpochMillis = time,
        title = key,
        body = null,
        sourceLabelSnapshot = "Delete test",
        contentFingerprint = null,
        normalizationWarnings = "",
        normalizationStrategyVersion = 1,
        titleOriginalCodePoints = key.length,
        bodyOriginalCodePoints = 0,
        hadContentIntent = false,
        contentIntentCreatorPackage = null,
        notificationActionCount = 0,
        isRemoved = false,
        removedAtEpochMillis = null,
        lastRemovalReason = null,
        lastCallbackKind = "POST_OR_UPDATE",
        runtimeSessionIdAtLastCapture = "test",
    )

    private fun healthEvidence(itemId: Long, time: Long) = HealthEvidenceEntity(
        kind = "NOTIFICATION_COMMITTED",
        occurredAtEpochMillis = time,
        runtimeSessionId = "test",
        listenerConnectionId = "connection",
        itemId = itemId,
    )
}
