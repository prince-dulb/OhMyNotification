package io.github.prince_dulb.ohmynotification.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxSourceIsolationTest {
    @Test
    fun compositeSourceFilterDoesNotMixAndroidUsersWithSamePackage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OmnDatabase::class.java).build()
        try {
            val dao = database.omnDao()
            dao.insertItem(item(sourcePackage = "example.same", sourceUserRef = "user-personal", time = 300L))
            val workItemId = dao.insertItem(
                item(sourcePackage = "example.same", sourceUserRef = "user-work", time = 200L),
            )
            dao.insertItem(item(sourcePackage = "example.other", sourceUserRef = "user-personal", time = 100L))

            assertEquals(
                listOf("user-work"),
                load(
                    dao,
                    InboxViewFilter(includedSources = setOf(AppUserKey("user-work", "example.same"))),
                ).map(NotificationItemEntity::sourceUserRef),
            )
            assertEquals(
                listOf("user-work", "user-personal"),
                load(
                    dao,
                    InboxViewFilter(
                        excludedSources = setOf(AppUserKey("user-personal", "example.same")),
                    ),
                ).map(NotificationItemEntity::sourceUserRef),
            )
            assertEquals(
                listOf("example.same", "example.other"),
                load(
                    dao,
                    InboxViewFilter(
                        includedSources = setOf(
                            AppUserKey("user-personal", "example.same"),
                            AppUserKey("user-personal", "example.other"),
                        ),
                    ),
                ).map(NotificationItemEntity::sourcePackage),
            )
            assertEquals(
                listOf("user-work"),
                load(
                    dao = dao,
                    viewFilter = InboxViewFilter(),
                    actionView = NotificationActionView.ACTIONABLE,
                    runtimeActionItemIds = setOf(workItemId),
                ).map(NotificationItemEntity::sourceUserRef),
            )
        } finally {
            database.close()
        }
    }

    private suspend fun load(
        dao: OmnDao,
        viewFilter: InboxViewFilter,
        actionView: NotificationActionView = NotificationActionView.UNAVAILABLE_ARCHIVE,
        runtimeActionItemIds: Set<Long> = emptySet(),
    ): List<NotificationItemEntity> {
        val result = dao.pageItemsFromSources(
            buildPageItemsQuery(viewFilter, actionView, runtimeActionItemIds),
        ).load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false,
            ),
        )
        return (result as PagingSource.LoadResult.Page).data
    }

    private fun item(
        sourcePackage: String,
        sourceUserRef: String,
        time: Long,
    ) = NotificationItemEntity(
        sourcePackage = sourcePackage,
        sourceUserRef = sourceUserRef,
        systemKey = "$sourceUserRef:$sourcePackage:$time",
        notificationId = time.toInt(),
        tag = null,
        lifecycleGeneration = 0,
        firstReceivedAtEpochMillis = time,
        lastUpdatedAtEpochMillis = time,
        sortTimeEpochMillis = time,
        originalPostTimeEpochMillis = time,
        title = null,
        body = null,
        sourceLabelSnapshot = sourcePackage,
        contentFingerprint = null,
        normalizationWarnings = "",
        normalizationStrategyVersion = 1,
        titleOriginalCodePoints = 0,
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
}
