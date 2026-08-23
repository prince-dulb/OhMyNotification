package io.github.prince_dulb.ohmynotification.testsource

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

internal fun grantNotificationPermission(context: Context) {
    val packageManager = context.packageManager
    val packageName = context.packageName
    if (
        packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, packageName) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
    assertEquals(
        PackageManager.PERMISSION_GRANTED,
        packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, packageName),
    )
}
