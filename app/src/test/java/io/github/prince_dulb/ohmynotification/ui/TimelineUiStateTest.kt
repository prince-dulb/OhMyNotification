package io.github.prince_dulb.ohmynotification.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineUiStateTest {
    @Test
    fun drawersAreExpandedByDefaultAndRespectUserOverride() {
        assertTrue(drawerExpanded(emptyMap(), anchorItemId = 7L))
        assertFalse(drawerExpanded(mapOf(7L to false), anchorItemId = 7L))
        assertTrue(drawerExpanded(mapOf(7L to true), anchorItemId = 7L))
    }

    @Test
    fun oneLargeDrawerDoesNotPrefetchFromItsTop() {
        assertFalse(
            shouldPrefetchTimeline(
                lastVisibleIndex = 1,
                totalItemCount = 2,
                lastVisibleBottom = 8_000,
                viewportEnd = 1_000,
                viewportSize = 1_000,
            ),
        )
    }

    @Test
    fun reachingLoadedContentBottomPrefetchesNextPage() {
        assertTrue(
            shouldPrefetchTimeline(
                lastVisibleIndex = 8,
                totalItemCount = 10,
                lastVisibleBottom = 1_050,
                viewportEnd = 1_000,
                viewportSize = 1_000,
            ),
        )
    }

    @Test
    fun distantListItemsDoNotPrefetch() {
        assertFalse(
            shouldPrefetchTimeline(
                lastVisibleIndex = 3,
                totalItemCount = 10,
                lastVisibleBottom = 950,
                viewportEnd = 1_000,
                viewportSize = 1_000,
            ),
        )
    }

    @Test
    fun actionPromptDistinguishesAvailableRecoveryAndUnavailableStates() {
        assertEquals("点按尝试打开原内容", notificationActionPrompt(true, true))
        assertEquals(
            "尝试重新获取并打开原内容（不一定成功）",
            notificationActionPrompt(false, true),
        )
        assertNull(notificationActionPrompt(false, false))
    }
}
