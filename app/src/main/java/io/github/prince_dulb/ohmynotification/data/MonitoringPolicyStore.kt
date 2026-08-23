package io.github.prince_dulb.ohmynotification.data

import android.content.Context
import io.github.prince_dulb.ohmynotification.core.model.MonitoringPolicySnapshot
import java.util.concurrent.atomic.AtomicReference

class MonitoringPolicyStore(
    context: Context,
    ownPackageName: String,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val alwaysExcluded = setOf(ownPackageName)
    private val current = AtomicReference(
        MonitoringPolicySnapshot(
            alwaysExcludedPackages = alwaysExcluded,
            userExcludedPackages = preferences.getStringSet(EXCLUDED_PACKAGES, emptySet()).orEmpty().toSet(),
            revision = preferences.getLong(REVISION, 0L),
        ),
    )

    fun snapshot(): MonitoringPolicySnapshot = current.get()

    @Synchronized
    fun replaceUserExclusions(packages: Set<String>) {
        val previous = current.get()
        if (previous.userExcludedPackages == packages) return
        val next = previous.copy(
            userExcludedPackages = packages.toSet(),
            revision = previous.revision + 1,
        )
        preferences.edit()
            .putStringSet(EXCLUDED_PACKAGES, next.userExcludedPackages)
            .putLong(REVISION, next.revision)
            .apply()
        current.set(next)
    }

    @Synchronized
    fun setExcluded(sourcePackage: String, excluded: Boolean) {
        val packages = current.get().userExcludedPackages.toMutableSet()
        if (excluded) packages += sourcePackage else packages -= sourcePackage
        replaceUserExclusions(packages)
    }

    private companion object {
        const val PREFERENCES_NAME = "omn-monitoring-policy"
        const val EXCLUDED_PACKAGES = "excluded-packages"
        const val REVISION = "revision"
    }
}
