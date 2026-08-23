package io.github.prince_dulb.ohmynotification.ui

import io.github.prince_dulb.ohmynotification.data.AppUserKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceDialogItemKeyTest {
    @Test
    fun dialogUsesBundleSaveableCollisionFreeStringKeys() {
        val personal = sourceDialogSaveableItemKey(
            AppUserKey("UserHandle{0}", "example.same"),
        )
        val work = sourceDialogSaveableItemKey(
            AppUserKey("UserHandle{10}", "example.same"),
        )

        assertEquals("v1:13:UserHandle{0}example.same", personal)
        assertNotEquals(personal, work)
    }
}
