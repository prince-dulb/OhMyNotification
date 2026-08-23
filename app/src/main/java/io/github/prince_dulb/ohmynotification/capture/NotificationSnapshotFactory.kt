package io.github.prince_dulb.ohmynotification.capture

import android.app.Notification
import android.app.PendingIntent
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import io.github.prince_dulb.ohmynotification.core.model.ActionCapabilitySet
import io.github.prince_dulb.ohmynotification.core.model.MonitoringPolicySnapshot
import io.github.prince_dulb.ohmynotification.core.model.NotificationIdentity
import io.github.prince_dulb.ohmynotification.core.model.NotificationObservation
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import io.github.prince_dulb.ohmynotification.core.model.RawVisibleContent
import io.github.prince_dulb.ohmynotification.core.model.SnapshotCopyWarning

sealed interface SnapshotResult {
    data class Captured(
        val observation: NotificationObservation,
        val runtimeAction: PendingIntent?,
    ) : SnapshotResult

    data class Excluded(val reason: ExclusionReason) : SnapshotResult
    data class Failed(val reason: FailureReason) : SnapshotResult
}

enum class ExclusionReason { SELF_PACKAGE, USER_POLICY }
enum class FailureReason { INVALID_IDENTITY }

class NotificationSnapshotFactory(
    private val ownPackageName: String,
    private val runtimeSessionId: String,
    private val clock: CaptureClock = AndroidCaptureClock,
) {
    fun capture(
        statusBarNotification: StatusBarNotification,
        callbackKind: ObservedCallbackKind,
        listenerConnectionId: String,
        policy: MonitoringPolicySnapshot,
        removalReason: Int? = null,
    ): SnapshotResult {
        val observedAt = clock.currentTimeMillis()
        val observedElapsed = clock.elapsedRealtimeNanos()
        val sourcePackage = runCatching { statusBarNotification.packageName }.getOrNull()
        val systemKey = runCatching { statusBarNotification.key }.getOrNull()
        if (sourcePackage.isNullOrBlank() || systemKey.isNullOrBlank()) {
            return SnapshotResult.Failed(FailureReason.INVALID_IDENTITY)
        }
        if (sourcePackage == ownPackageName) {
            return SnapshotResult.Excluded(ExclusionReason.SELF_PACKAGE)
        }
        if (policy.excludes(sourcePackage)) {
            return SnapshotResult.Excluded(ExclusionReason.USER_POLICY)
        }

        val identity = NotificationIdentity(
            sourcePackage = sourcePackage,
            sourceUserRef = statusBarNotification.user.toString(),
            systemKey = systemKey,
            notificationId = statusBarNotification.id,
            tag = statusBarNotification.tag,
        )
        if (callbackKind == ObservedCallbackKind.REMOVED) {
            return SnapshotResult.Captured(
                observation = NotificationObservation(
                    runtimeSessionId = runtimeSessionId,
                    listenerConnectionId = listenerConnectionId,
                    callbackKind = callbackKind,
                    identity = identity,
                    postTimeEpochMillis = statusBarNotification.postTime,
                    observedAtEpochMillis = observedAt,
                    observedElapsedNanos = observedElapsed,
                    content = EMPTY_CONTENT,
                    copyWarnings = emptySet(),
                    actionCapabilities = EMPTY_ACTION_CAPABILITIES,
                    policyRevision = policy.revision,
                    removalReason = removalReason,
                ),
                runtimeAction = null,
            )
        }

        val notification = statusBarNotification.notification
        val extras = notification.extras ?: Bundle.EMPTY
        val warnings = linkedSetOf<SnapshotCopyWarning>()
        val content = RawVisibleContent(
            title = copyText(extras, Notification.EXTRA_TITLE, SnapshotCopyWarning.TITLE_COPY_FAILED, warnings),
            text = copyText(extras, Notification.EXTRA_TEXT, SnapshotCopyWarning.TEXT_COPY_FAILED, warnings),
            bigText = copyText(extras, Notification.EXTRA_BIG_TEXT, SnapshotCopyWarning.BIG_TEXT_COPY_FAILED, warnings),
            subText = copyText(extras, Notification.EXTRA_SUB_TEXT, SnapshotCopyWarning.SUB_TEXT_COPY_FAILED, warnings),
            summaryText = copyText(
                extras,
                Notification.EXTRA_SUMMARY_TEXT,
                SnapshotCopyWarning.SUMMARY_TEXT_COPY_FAILED,
                warnings,
            ),
            infoText = copyText(extras, Notification.EXTRA_INFO_TEXT, SnapshotCopyWarning.INFO_TEXT_COPY_FAILED, warnings),
            category = notification.category,
            channelId = notification.channelId,
            groupKey = statusBarNotification.groupKey,
        )
        val action = notification.contentIntent
        return SnapshotResult.Captured(
            observation = NotificationObservation(
                runtimeSessionId = runtimeSessionId,
                listenerConnectionId = listenerConnectionId,
                callbackKind = callbackKind,
                identity = identity,
                postTimeEpochMillis = statusBarNotification.postTime,
                observedAtEpochMillis = observedAt,
                observedElapsedNanos = observedElapsed,
                content = content,
                copyWarnings = warnings,
                actionCapabilities = ActionCapabilitySet(
                    hasContentIntent = action != null,
                    contentIntentCreatorPackage = action?.creatorPackage,
                    notificationActionCount = notification.actions?.size ?: 0,
                    hasDeleteIntent = notification.deleteIntent != null,
                ),
                policyRevision = policy.revision,
                removalReason = null,
            ),
            runtimeAction = action,
        )
    }

    private fun copyText(
        extras: Bundle,
        key: String,
        warning: SnapshotCopyWarning,
        warnings: MutableSet<SnapshotCopyWarning>,
    ): String? = try {
        extras.getCharSequence(key)?.toString()
    } catch (_: RuntimeException) {
        warnings += warning
        null
    }

    private companion object {
        val EMPTY_CONTENT = RawVisibleContent(null, null, null, null, null, null, null, null, null)
        val EMPTY_ACTION_CAPABILITIES = ActionCapabilitySet(false, null, 0, false)
    }
}

fun interface CaptureClock {
    fun currentTimeMillis(): Long

    fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

private object AndroidCaptureClock : CaptureClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
