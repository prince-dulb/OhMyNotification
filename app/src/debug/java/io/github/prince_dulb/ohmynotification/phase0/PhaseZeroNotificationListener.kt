package io.github.prince_dulb.ohmynotification.phase0

import android.app.Notification
import android.app.PendingIntent
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

class PhaseZeroNotificationListener : NotificationListenerService() {
    private val writerExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "omn-phase0-evidence-writer").apply { isDaemon = true }
    }
    private lateinit var evidenceWriter: PhaseZeroEvidenceWriter
    private var connectionId: String? = null

    override fun onCreate() {
        super.onCreate()
        evidenceWriter = PhaseZeroEvidenceWriter(filesDir)
        enqueue(
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("eventKind", "PROCESS_CREATED")
                .put("runtimeSessionId", RUNTIME_SESSION_ID)
                .put("observedAt", Instant.now().toString())
                .put("observedElapsedNanos", SystemClock.elapsedRealtimeNanos()),
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val currentConnection = UUID.randomUUID().toString()
        connectionId = currentConnection
        enqueue(lifecycleEvent("LISTENER_CONNECTED", currentConnection))

        val active = runCatching { activeNotifications?.toList().orEmpty() }
        active.onSuccess { notifications ->
            notifications.forEach { notification ->
                captureNotification(
                    statusBarNotification = notification,
                    callbackKind = "RECOVERY_SNAPSHOT",
                    removalReason = null,
                )
            }
            enqueue(
                lifecycleEvent("RECOVERY_SNAPSHOT_COMPLETED", currentConnection)
                    .put("activeNotificationCount", notifications.size),
            )
        }.onFailure { failure ->
            enqueue(
                lifecycleEvent("RECOVERY_SNAPSHOT_FAILED", currentConnection)
                    .put("failureType", failure.javaClass.simpleName),
            )
        }
    }

    override fun onListenerDisconnected() {
        val disconnectedConnection = connectionId
        enqueue(lifecycleEvent("LISTENER_DISCONNECTED", disconnectedConnection))
        connectionId = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(
        statusBarNotification: StatusBarNotification,
        rankingMap: RankingMap,
    ) {
        captureNotification(
            statusBarNotification = statusBarNotification,
            callbackKind = "POST_OR_UPDATE",
            removalReason = null,
        )
    }

    override fun onNotificationRemoved(
        statusBarNotification: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        captureNotification(
            statusBarNotification = statusBarNotification,
            callbackKind = "REMOVED",
            removalReason = reason,
        )
    }

    override fun onDestroy() {
        enqueue(lifecycleEvent("PROCESS_DESTROYED", connectionId))
        writerExecutor.shutdown()
        super.onDestroy()
    }

    private fun captureNotification(
        statusBarNotification: StatusBarNotification,
        callbackKind: String,
        removalReason: Int?,
    ) {
        val observedAt = Instant.now().toString()
        val observedElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val sourcePackage = statusBarNotification.packageName.orEmpty()
        val identity = JSONObject()
            .put("sourcePackage", sourcePackage)
            .put("sourceOpPackage", jsonNullable(statusBarNotification.opPkg))
            .put("sourceUid", statusBarNotification.uid)
            .put("sourceUserRef", statusBarNotification.user.toString())
            .put("systemKey", statusBarNotification.key)
            .put("notificationId", statusBarNotification.id)
            .put("tag", jsonNullable(statusBarNotification.tag))
            .put("postTimeEpochMillis", statusBarNotification.postTime)
            .put("groupKey", jsonNullable(statusBarNotification.groupKey))
            .put("overrideGroupKey", jsonNullable(statusBarNotification.overrideGroupKey))
            .put("isClearable", statusBarNotification.isClearable)
            .put("isOngoing", statusBarNotification.isOngoing)

        val event = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("eventId", UUID.randomUUID().toString())
            .put("eventKind", "NOTIFICATION_CALLBACK")
            .put("callbackKind", callbackKind)
            .put("runtimeSessionId", RUNTIME_SESSION_ID)
            .put("listenerConnectionId", jsonNullable(connectionId))
            .put("observedAt", observedAt)
            .put("observedElapsedNanos", observedElapsedNanos)
            .put("identity", identity)
            .put("removalReason", jsonNullable(removalReason))

        if (sourcePackage == packageName) {
            event.put("captureResult", "EXCLUDED_SELF_PACKAGE")
            enqueue(event)
            return
        }

        if (callbackKind == "REMOVED") {
            event.put("captureResult", "CAPTURED_IDENTITY_ONLY")
            enqueue(event)
            return
        }

        val notification = statusBarNotification.notification
        val eventId = event.getString("eventId")
        RuntimeActionRegistry.put(eventId, notification.contentIntent)
        event
            .put("captureResult", "CAPTURED_PRIVATE_PHASE0")
            .put("notification", notificationProjection(notification))
            .put("runtimeActionRegistrySize", RuntimeActionRegistry.size())
        enqueue(event)
    }

    private fun notificationProjection(notification: Notification): JSONObject {
        val extras = notification.extras ?: Bundle.EMPTY
        return JSONObject()
            .put("flags", notification.flags)
            .put("category", jsonNullable(notification.category))
            .put("channelId", jsonNullable(notification.channelId))
            .put("group", jsonNullable(notification.group))
            .put("sortKey", jsonNullable(notification.sortKey))
            .put("whenEpochMillis", notification.`when`)
            .put("visibility", notification.visibility)
            .put("number", notification.number)
            .put("badgeIconType", notification.badgeIconType)
            .put("visibleContent", visibleContentProjection(extras))
            .put("extraFields", extraFieldsProjection(extras))
            .put("contentIntent", pendingIntentProjection(notification.contentIntent))
            .put("deleteIntent", pendingIntentProjection(notification.deleteIntent))
            .put("fullScreenIntent", pendingIntentProjection(notification.fullScreenIntent))
            .put("actions", actionsProjection(notification.actions))
    }

    private fun visibleContentProjection(extras: Bundle): JSONObject = JSONObject().apply {
        VISIBLE_EXTRA_KEYS.forEach { (outputKey, androidKey) ->
            put(outputKey, safeTextProjection(extras, androidKey))
        }
    }

    private fun safeTextProjection(extras: Bundle, key: String): Any {
        if (!extras.containsKey(key)) {
            return JSONObject.NULL
        }
        return try {
            boundedText(readBundleValue(extras, key)?.toString())
        } catch (failure: RuntimeException) {
            JSONObject()
                .put("copyFailure", failure.javaClass.simpleName)
        }
    }

    private fun extraFieldsProjection(extras: Bundle): JSONArray = JSONArray().apply {
        extras.keySet().sorted().forEach { key ->
            val projected = JSONObject().put("key", key)
            try {
                val value = readBundleValue(extras, key)
                projected.put("valueType", value?.javaClass?.name)
                when (value) {
                    null -> projected.put("value", JSONObject.NULL)
                    is CharSequence,
                    is Number,
                    is Boolean,
                    is Char,
                    -> projected.put("value", boundedText(value.toString()))
                    is Array<*> -> projected.put("arrayLength", value.size)
                    is IntArray -> projected.put("arrayLength", value.size)
                    is LongArray -> projected.put("arrayLength", value.size)
                    is BooleanArray -> projected.put("arrayLength", value.size)
                    else -> projected.put("valueCaptured", false)
                }
            } catch (failure: RuntimeException) {
                projected.put("copyFailure", failure.javaClass.simpleName)
            }
            put(projected)
        }
    }

    @Suppress("DEPRECATION")
    private fun readBundleValue(extras: Bundle, key: String): Any? = extras.get(key)

    private fun actionsProjection(actions: Array<Notification.Action>?): JSONArray = JSONArray().apply {
        actions.orEmpty().forEachIndexed { index, action ->
            put(
                JSONObject()
                    .put("index", index)
                    .put("title", boundedText(runCatching { action.title?.toString() }.getOrNull()))
                    .put("semanticAction", action.semanticAction)
                    .put("allowGeneratedReplies", action.allowGeneratedReplies)
                    .put("remoteInputCount", action.remoteInputs?.size ?: 0)
                    .put("actionIntent", pendingIntentProjection(action.actionIntent)),
            )
        }
    }

    private fun pendingIntentProjection(pendingIntent: PendingIntent?): Any {
        if (pendingIntent == null) {
            return JSONObject.NULL
        }
        return JSONObject()
            .put("creatorPackage", pendingIntent.creatorPackage)
            .put("creatorUid", pendingIntent.creatorUid)
            .put("isImmutable", pendingIntent.isImmutable)
            .put("isActivity", pendingIntent.isActivity)
            .put("isBroadcast", pendingIntent.isBroadcast)
            .put("isService", pendingIntent.isService)
            .put("isForegroundService", pendingIntent.isForegroundService)
    }

    private fun lifecycleEvent(eventKind: String, currentConnectionId: String?): JSONObject =
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("eventKind", eventKind)
            .put("runtimeSessionId", RUNTIME_SESSION_ID)
            .put("listenerConnectionId", jsonNullable(currentConnectionId))
            .put("observedAt", Instant.now().toString())
            .put("observedElapsedNanos", SystemClock.elapsedRealtimeNanos())

    private fun boundedText(value: String?): Any {
        if (value == null) {
            return JSONObject.NULL
        }
        if (value.length <= MAX_TEXT_CHARS) {
            return value
        }
        return JSONObject()
            .put("value", value.substring(0, MAX_TEXT_CHARS))
            .put("truncated", true)
            .put("originalLength", value.length)
    }

    private fun jsonNullable(value: Any?): Any = value ?: JSONObject.NULL

    private fun enqueue(event: JSONObject) {
        val serialized = event.toString()
        writerExecutor.execute {
            runCatching { evidenceWriter.append(serialized) }
                .onFailure { failure ->
                    Log.e(LOG_TAG, "EVIDENCE_WRITE_FAILED:${failure.javaClass.simpleName}")
                }
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_TEXT_CHARS = 16_384
        const val LOG_TAG = "OMNPhase0"
        val RUNTIME_SESSION_ID: String = UUID.randomUUID().toString()
        val VISIBLE_EXTRA_KEYS = listOf(
            "title" to Notification.EXTRA_TITLE,
            "titleBig" to Notification.EXTRA_TITLE_BIG,
            "text" to Notification.EXTRA_TEXT,
            "bigText" to Notification.EXTRA_BIG_TEXT,
            "subText" to Notification.EXTRA_SUB_TEXT,
            "summaryText" to Notification.EXTRA_SUMMARY_TEXT,
            "infoText" to Notification.EXTRA_INFO_TEXT,
            "template" to Notification.EXTRA_TEMPLATE,
            "textLines" to Notification.EXTRA_TEXT_LINES,
        )
    }
}

internal enum class RuntimeActionDispatchStatus {
    ACCEPTED,
    CANCELED,
    NOT_FOUND,
}

internal data class RuntimeActionDispatchResult(
    val status: RuntimeActionDispatchStatus,
    val registrySizeAfter: Int,
)

internal object RuntimeActionRegistry {
    private const val MAX_HANDLES = 512
    private val handles = object : LinkedHashMap<String, PendingIntent>(MAX_HANDLES + 1, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?): Boolean =
            size > MAX_HANDLES
    }

    @Synchronized
    fun put(eventId: String, pendingIntent: PendingIntent?) {
        if (pendingIntent != null) {
            handles[eventId] = pendingIntent
        }
    }

    @Synchronized
    fun sendLatestForCreator(creatorPackage: String): RuntimeActionDispatchResult {
        val entry = handles.entries.lastOrNull { (_, pendingIntent) ->
            pendingIntent.creatorPackage == creatorPackage
        } ?: return RuntimeActionDispatchResult(
            status = RuntimeActionDispatchStatus.NOT_FOUND,
            registrySizeAfter = handles.size,
        )

        return try {
            entry.value.send()
            RuntimeActionDispatchResult(
                status = RuntimeActionDispatchStatus.ACCEPTED,
                registrySizeAfter = handles.size,
            )
        } catch (_: PendingIntent.CanceledException) {
            handles.remove(entry.key)
            RuntimeActionDispatchResult(
                status = RuntimeActionDispatchStatus.CANCELED,
                registrySizeAfter = handles.size,
            )
        }
    }

    @Synchronized
    fun size(): Int = handles.size
}

private class PhaseZeroEvidenceWriter(filesDirectory: File) {
    private val evidenceDirectory = File(filesDirectory, "phase0")
    private val evidenceFile = File(evidenceDirectory, FILE_NAME)
    private var limitMarkerWritten = false

    @Synchronized
    fun append(serializedEvent: String) {
        if (!evidenceDirectory.exists() && !evidenceDirectory.mkdirs()) {
            error("EVIDENCE_DIRECTORY_UNAVAILABLE")
        }
        val payload = "$serializedEvent\n".toByteArray(StandardCharsets.UTF_8)
        if (evidenceFile.length() + payload.size > MAX_FILE_BYTES) {
            appendLimitMarker()
            return
        }
        FileOutputStream(evidenceFile, true).buffered().use { output ->
            output.write(payload)
        }
    }

    private fun appendLimitMarker() {
        if (limitMarkerWritten) {
            return
        }
        limitMarkerWritten = true
        val marker = JSONObject()
            .put("schemaVersion", 1)
            .put("eventKind", "CAPTURE_LIMIT_REACHED")
            .put("observedAt", Instant.now().toString())
            .toString() + "\n"
        evidenceFile.appendText(marker, StandardCharsets.UTF_8)
    }

    private companion object {
        const val FILE_NAME = "notification-events-v1.jsonl"
        const val MAX_FILE_BYTES = 16L * 1024L * 1024L
    }
}
