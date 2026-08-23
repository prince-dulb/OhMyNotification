package io.github.prince_dulb.ohmynotification.capture

import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

class SourceLabelResolver(private val packageManager: PackageManager) {
    private val cache = ConcurrentHashMap<String, String>()

    fun resolve(sourcePackage: String): String = cache.getOrPut(sourcePackage) {
        runCatching {
            val info = packageManager.getApplicationInfo(
                sourcePackage,
                PackageManager.ApplicationInfoFlags.of(0),
            )
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sourcePackage)
    }
}
