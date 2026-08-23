package io.github.prince_dulb.ohmynotification.core.model

enum class ObservedCallbackKind {
    POST_OR_UPDATE,
    REMOVED,
    RECOVERY_SNAPSHOT,
}

data class NotificationIdentity(
    val sourcePackage: String,
    val sourceUserRef: String,
    val systemKey: String,
    val notificationId: Int,
    val tag: String?,
)

data class RawVisibleContent(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val summaryText: String?,
    val infoText: String?,
    val category: String?,
    val channelId: String?,
    val groupKey: String?,
)

enum class SnapshotCopyWarning {
    TITLE_COPY_FAILED,
    TEXT_COPY_FAILED,
    BIG_TEXT_COPY_FAILED,
    SUB_TEXT_COPY_FAILED,
    SUMMARY_TEXT_COPY_FAILED,
    INFO_TEXT_COPY_FAILED,
}

data class ActionCapabilitySet(
    val hasContentIntent: Boolean,
    val contentIntentCreatorPackage: String?,
    val notificationActionCount: Int,
    val hasDeleteIntent: Boolean,
)

data class NotificationObservation(
    val runtimeSessionId: String,
    val listenerConnectionId: String,
    val callbackKind: ObservedCallbackKind,
    val identity: NotificationIdentity,
    val postTimeEpochMillis: Long,
    val observedAtEpochMillis: Long,
    val observedElapsedNanos: Long,
    val content: RawVisibleContent,
    val copyWarnings: Set<SnapshotCopyWarning>,
    val actionCapabilities: ActionCapabilitySet,
    val policyRevision: Long,
    val removalReason: Int?,
)

data class MonitoringPolicySnapshot(
    val alwaysExcludedPackages: Set<String>,
    val userExcludedPackages: Set<String>,
    val revision: Long,
) {
    fun excludes(sourcePackage: String): Boolean =
        sourcePackage in alwaysExcludedPackages || sourcePackage in userExcludedPackages
}
