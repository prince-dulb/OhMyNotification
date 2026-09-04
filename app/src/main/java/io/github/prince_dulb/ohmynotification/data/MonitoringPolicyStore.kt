package io.github.prince_dulb.ohmynotification.data

import io.github.prince_dulb.ohmynotification.core.model.MonitoringPolicySnapshot
import io.github.prince_dulb.ohmynotification.core.model.NotificationType
import java.util.concurrent.atomic.AtomicReference

class MonitoringPolicyStore(
    ownPackageName: String,
) {
    private val alwaysExcluded = setOf(ownPackageName)
    private val current = AtomicReference(
        MonitoringPolicySnapshot(
            alwaysExcludedPackages = alwaysExcluded,
            userExcludedPackages = emptySet(),
            recordedNotificationTypes = NotificationType.entries.toSet(),
            revision = 0L,
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
        current.set(next)
    }

    @Synchronized
    fun setExcluded(sourcePackage: String, excluded: Boolean) {
        val packages = current.get().userExcludedPackages.toMutableSet()
        if (excluded) packages += sourcePackage else packages -= sourcePackage
        replaceUserExclusions(packages)
    }

    @Synchronized
    fun replaceRecordedNotificationTypes(types: Set<NotificationType>) {
        val previous = current.get()
        if (previous.recordedNotificationTypes == types) return
        current.set(
            previous.copy(
                recordedNotificationTypes = types.toSet(),
                revision = previous.revision + 1,
            ),
        )
    }
}
