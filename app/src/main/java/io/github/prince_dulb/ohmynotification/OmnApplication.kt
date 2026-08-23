package io.github.prince_dulb.ohmynotification

import android.app.Application
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerComponent
import io.github.prince_dulb.ohmynotification.capture.NotificationSnapshotFactory
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStore
import io.github.prince_dulb.ohmynotification.capture.SourceLabelResolver
import io.github.prince_dulb.ohmynotification.capture.StatusNotificationController
import io.github.prince_dulb.ohmynotification.core.normalize.HmacSha256Fingerprinter
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizationLimits
import io.github.prince_dulb.ohmynotification.core.normalize.NotificationNormalizer
import io.github.prince_dulb.ohmynotification.data.InstallKeyProvider
import io.github.prince_dulb.ohmynotification.data.MonitoringPolicyStore
import io.github.prince_dulb.ohmynotification.data.NotificationRepository
import io.github.prince_dulb.ohmynotification.data.OmnDatabase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OmnApplication : Application() {
    lateinit var graph: OmnAppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = OmnAppGraph(this)
        graph.serialScope.launch {
            graph.repository.reconcilePolicyFromDatabase()
            graph.repository.recordHealth(
                kind = "PROCESS_STARTED",
                occurredAtEpochMillis = System.currentTimeMillis(),
                runtimeSessionId = graph.runtimeSessionId,
                listenerConnectionId = null,
            )
            graph.recordCurrentHealthFacets()
        }
    }
}

class OmnAppGraph(application: Application) {
    private val applicationContext = application
    private var lastAccessKind: String? = null
    private var lastPermissionKind: String? = null
    private var lastChannelKind: String? = null
    val runtimeSessionId: String = UUID.randomUUID().toString()
    val serialScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    val policyStore = MonitoringPolicyStore(application, application.packageName)
    val runtimeActionStore = RuntimeActionStore()
    val sourceLabelResolver = SourceLabelResolver(application.packageManager, application.packageName)
    val statusNotificationController = StatusNotificationController(application)
    val snapshotFactory = NotificationSnapshotFactory(
        ownPackageName = application.packageName,
        runtimeSessionId = runtimeSessionId,
    )
    val repository = NotificationRepository(
        database = OmnDatabase.get(application),
        normalizer = NotificationNormalizer(
            limits = NormalizationLimits(),
            fingerprinter = HmacSha256Fingerprinter(
                InstallKeyProvider.contentFingerprintKey(application),
            ),
        ),
        runtimeActionStore = runtimeActionStore,
        policyStore = policyStore,
    )

    suspend fun recordCurrentHealthFacets() {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        val accessKind = if (notificationManager.isNotificationListenerAccessGranted(
                OmnNotificationListenerComponent.componentName(applicationContext),
            )
        ) {
            "ACCESS_GRANTED"
        } else {
            "ACCESS_NOT_GRANTED"
        }
        val permissionKind = if (
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            "STATUS_PERMISSION_GRANTED"
        } else {
            "STATUS_PERMISSION_DENIED"
        }
        val channelKind = if (statusNotificationController.isChannelEnabled()) {
            "STATUS_CHANNEL_ENABLED"
        } else {
            "STATUS_CHANNEL_DISABLED"
        }
        val changedKinds = synchronized(this) {
            buildList {
                if (lastAccessKind != accessKind) add(accessKind)
                if (lastPermissionKind != permissionKind) add(permissionKind)
                if (lastChannelKind != channelKind) add(channelKind)
                lastAccessKind = accessKind
                lastPermissionKind = permissionKind
                lastChannelKind = channelKind
            }
        }
        val observedAt = System.currentTimeMillis()
        changedKinds.forEach { kind ->
            repository.recordHealth(
                kind = kind,
                occurredAtEpochMillis = observedAt,
                runtimeSessionId = runtimeSessionId,
                listenerConnectionId = null,
            )
        }
    }
}
