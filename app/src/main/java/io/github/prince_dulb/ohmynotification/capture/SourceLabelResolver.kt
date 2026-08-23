package io.github.prince_dulb.ohmynotification.capture

import android.content.Intent
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

data class LaunchableSource(val sourcePackage: String, val sourceLabel: String)

class SourceLabelResolver(
    private val packageManager: PackageManager,
    private val ownPackageName: String,
) {
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

    fun launchableSources(): List<LaunchableSource> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0),
        ).asSequence()
            .map { info -> info.activityInfo.packageName }
            .filter { sourcePackage -> sourcePackage.isNotBlank() && sourcePackage != ownPackageName }
            .distinct()
            .map { sourcePackage -> LaunchableSource(sourcePackage, resolve(sourcePackage)) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { source -> source.sourceLabel })
            .toList()
    }
}
