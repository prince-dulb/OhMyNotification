package io.github.prince_dulb.ohmynotification.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.prince_dulb.ohmynotification.R
import io.github.prince_dulb.ohmynotification.capture.ListenerRebindResult
import io.github.prince_dulb.ohmynotification.capture.ListenerRebindTrigger
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStatus
import io.github.prince_dulb.ohmynotification.capture.LaunchableSource
import io.github.prince_dulb.ohmynotification.core.health.HealthFact
import io.github.prince_dulb.ohmynotification.core.health.HealthTimelineDeriver
import io.github.prince_dulb.ohmynotification.core.health.HealthTimelineQuery
import io.github.prince_dulb.ohmynotification.core.health.StatusPresentationDeriver
import io.github.prince_dulb.ohmynotification.core.health.StatusPresentationState
import io.github.prince_dulb.ohmynotification.data.NotificationItemEntity
import io.github.prince_dulb.ohmynotification.data.NotificationRepository
import io.github.prince_dulb.ohmynotification.data.AppUserKey
import io.github.prince_dulb.ohmynotification.data.InboxSourceKeyCodec
import io.github.prince_dulb.ohmynotification.data.SourceSummaryRow
import io.github.prince_dulb.ohmynotification.core.timeline.TimelineGrouper
import io.github.prince_dulb.ohmynotification.core.timeline.TimelineGroupingCandidate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUiState(
    val listenerAccessGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val listenerConnectionObserved: Boolean = false,
    val statusNotificationGranted: Boolean = false,
    val statusNotificationChannelEnabled: Boolean = true,
)

@Composable
internal fun OmnAppScreen(
    state: AppUiState,
    repository: NotificationRepository,
    includedSourceKeys: Flow<Set<AppUserKey>>,
    currentUserRef: String,
    currentRuntimeSessionId: String,
    currentListenerConnectionId: String?,
    loadLaunchableSources: () -> List<LaunchableSource>,
    onOpenNotificationAccess: () -> Unit,
    onRequestStatusNotification: () -> Unit,
    onOpenStatusChannel: () -> Unit,
    onRequestListenerRebind: (ListenerRebindTrigger) -> ListenerRebindResult,
    onSetSourceExcluded: suspend (String, Boolean) -> Boolean,
    onApplyIncludedSources: suspend (Set<AppUserKey>) -> Boolean,
    onOpenNotification: (NotificationItemEntity) -> RuntimeActionStatus,
    onOpenSourceApp: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val sources by repository.sourceSummaries.collectAsStateWithLifecycle(emptyList())
    val exclusions by repository.excludedSources.collectAsStateWithLifecycle(emptyList())
    val excludedPackages = remember(exclusions) {
        exclusions.mapTo(mutableSetOf()) { exclusion -> exclusion.sourcePackage }
    }
    val selectedSources by includedSourceKeys.collectAsStateWithLifecycle(emptySet())
    val launchableSources by produceState(emptyList<LaunchableSource>()) {
        value = withContext(Dispatchers.IO) { loadLaunchableSources() }
    }
    val filterChoices = remember(sources, launchableSources, selectedSources, currentUserRef) {
        val recorded = sources.associateBy { source ->
            AppUserKey(source.sourceUserRef, source.sourcePackage)
        }
        val launchable = launchableSources.associateBy(LaunchableSource::sourcePackage)
        val allKeys = (recorded.keys + selectedSources).distinct()
        val userCountByPackage = allKeys.groupingBy(AppUserKey::sourcePackage).eachCount()
        allKeys
            .map { source ->
                val summary = recorded[source]
                val installed = launchable[source.sourcePackage]
                SourceChoice(
                    source = source,
                    sourceLabel = summary?.sourceLabelSnapshot ?: installed?.sourceLabel ?: source.sourcePackage,
                    profileLabel = when {
                        userCountByPackage[source.sourcePackage] == 1 -> null
                        source.sourceUserRef == currentUserRef -> "当前资料"
                        else -> "其他资料"
                    },
                    recordCount = summary?.recordCount ?: 0L,
                )
            }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, SourceChoice::sourceLabel)
                    .thenBy { choice -> choice.source.sourceUserRef },
            )
    }
    val exclusionChoices = remember(sources, launchableSources, currentUserRef) {
        val recordedByPackage = sources.groupBy(SourceSummaryRow::sourcePackage)
        val launchableByPackage = launchableSources.associateBy(LaunchableSource::sourcePackage)
        (launchableByPackage.keys + recordedByPackage.keys)
            .distinct()
            .map { sourcePackage ->
                SourceChoice(
                    source = AppUserKey(currentUserRef, sourcePackage),
                    sourceLabel = recordedByPackage[sourcePackage]
                        ?.maxByOrNull(SourceSummaryRow::latestTimeEpochMillis)
                        ?.sourceLabelSnapshot
                        ?: launchableByPackage[sourcePackage]?.sourceLabel
                        ?: sourcePackage,
                    profileLabel = null,
                    recordCount = recordedByPackage[sourcePackage]
                        .orEmpty()
                        .sumOf(SourceSummaryRow::recordCount),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SourceChoice::sourceLabel))
    }
    var filterDraft by remember { mutableStateOf(emptySet<AppUserKey>()) }
    var filterSaving by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<SourceDialog?>(null) }
    var rebindFeedback by remember { mutableStateOf<String?>(null) }
    val expandedDrawers = remember { mutableStateMapOf<Long, Boolean>() }
    val snackbar = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val pagingFlow = remember(selectedSources) { repository.pagedItems(selectedSources) }
    val pagingItems = pagingFlow.collectAsLazyPagingItems()
    val loadedItems = pagingItems.itemSnapshotList.items
    val nodes = remember(loadedItems) { groupTimeline(loadedItems) }
    val healthStart = loadedItems.lastOrNull()?.firstReceivedAtEpochMillis ?: Long.MAX_VALUE
    val healthEvidence by remember(healthStart) {
        repository.healthEvidenceSince(healthStart)
    }.collectAsStateWithLifecycle(emptyList())
    val observedNow = remember(loadedItems, healthEvidence, currentListenerConnectionId) {
        System.currentTimeMillis()
    }
    val healthIntervals = remember(
        healthStart,
        observedNow,
        healthEvidence,
        currentRuntimeSessionId,
        currentListenerConnectionId,
    ) {
        if (healthStart == Long.MAX_VALUE) {
            emptyList()
        } else {
            HealthTimelineDeriver.derive(
                facts = healthEvidence.map { evidence ->
                    HealthFact(
                        evidenceId = evidence.evidenceId,
                        kind = evidence.kind,
                        occurredAtEpochMillis = evidence.occurredAtEpochMillis,
                        runtimeSessionId = evidence.runtimeSessionId,
                        listenerConnectionId = evidence.listenerConnectionId,
                    )
                },
                query = HealthTimelineQuery(
                    rangeStartEpochMillis = healthStart,
                    rangeEndEpochMillis = maxOf(observedNow, healthStart + 1L),
                    currentRuntimeSessionId = currentRuntimeSessionId,
                    currentListenerConnectionId = currentListenerConnectionId,
                ),
            )
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(state.listenerConnected) {
        if (state.listenerConnected) rebindFeedback = null
    }

    LaunchedEffect(nodes.size, loadedItems.size) {
        if (nodes.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { visibleIndex -> visibleIndex >= nodes.lastIndex - 2 }
            .collect { pagingItems[loadedItems.lastIndex] }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    InboxHeader(
                        state = state,
                        selectedCount = selectedSources.size,
                        onOpenFilters = {
                            filterDraft = selectedSources
                            filterSaving = false
                            dialog = SourceDialog.FILTER
                        },
                        onOpenExclusions = { dialog = SourceDialog.EXCLUDE },
                        onOpenNotificationAccess = onOpenNotificationAccess,
                        onRequestStatusNotification = onRequestStatusNotification,
                        onOpenStatusChannel = onOpenStatusChannel,
                        rebindFeedback = rebindFeedback,
                        onRequestListenerRebind = {
                            val result = onRequestListenerRebind(ListenerRebindTrigger.USER)
                            val message = result.userMessage()
                            rebindFeedback = message
                            coroutineScope.launch { snackbar.showSnackbar(message) }
                        },
                    )
                }

                if (nodes.isEmpty() && pagingItems.loadState.refresh is LoadState.NotLoading) {
                    item(key = "empty") { EmptyInbox() }
                }

                itemsIndexed(nodes, key = { _, node -> node.key }) { index, node ->
                    val next = nodes.getOrNull(index + 1)
                    val confirmedToNext = if (next == null) {
                        null
                    } else {
                        HealthTimelineDeriver.isFullyConfirmed(
                            intervals = healthIntervals,
                            startEpochMillis = next.anchor.firstReceivedAtEpochMillis,
                            endEpochMillis = node.anchor.firstReceivedAtEpochMillis,
                        )
                    }
                    TimelineNodeCard(
                        node = node,
                        confirmedToNext = confirmedToNext,
                        expanded = expandedDrawers[node.anchor.itemId] == true,
                        onToggle = {
                            expandedDrawers[node.anchor.itemId] =
                                expandedDrawers[node.anchor.itemId] != true
                        },
                        onOpen = { item ->
                            val status = onOpenNotification(item)
                            val message = when (status) {
                                RuntimeActionStatus.ACCEPTED -> "已交给原应用打开"
                                RuntimeActionStatus.CANCELED -> "原应用已取消这次跳转"
                                RuntimeActionStatus.NOT_FOUND -> "本次进程中没有可用跳转"
                                RuntimeActionStatus.SECURITY_REJECTED -> "系统拒绝了这次跳转"
                            }
                            snackbar.showSnackbar(message)
                            status
                        },
                        hasRuntimeAction = repository::hasRuntimeAction,
                        excludedPackages = excludedPackages,
                        onSetSourceExcluded = { item, excluded ->
                            val saved = onSetSourceExcluded(item.sourcePackage, excluded)
                            coroutineScope.launch {
                                snackbar.showSnackbar(
                                    if (saved && excluded) {
                                        "已不再记录 ${item.sourceName()} 的新通知"
                                    } else if (saved) {
                                        "已重新记录 ${item.sourceName()} 的新通知"
                                    } else {
                                        "未能保存设置，监控范围没有改变"
                                    },
                                )
                            }
                            saved
                        },
                        onOpenSource = { sourcePackage ->
                            val opened = onOpenSourceApp(sourcePackage)
                            snackbar.showSnackbar(
                                if (opened) "已打开来源应用；这不是原内容的精确跳转" else "来源应用当前无法打开",
                            )
                        },
                    )
                }

                if (pagingItems.loadState.append is LoadState.Loading) {
                    item(key = "loading") {
                        Text(
                            text = "正在载入更早的记录…",
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
            )
        }
    }

    when (dialog) {
        SourceDialog.FILTER -> SourceSelectionDialog(
            title = "只查看这些应用",
            sources = filterChoices,
            checkedSources = filterDraft,
            emptyMeansAll = true,
            supportingText = null,
            onToggle = { source, checked ->
                filterDraft = filterDraft.toMutableSet().apply {
                    if (checked) add(source) else remove(source)
                }
            },
            onClear = { filterDraft = emptySet() },
            confirmLabel = if (filterSaving) "保存中…" else "应用",
            confirmEnabled = !filterSaving,
            showCancel = true,
            onConfirm = {
                if (!filterSaving) {
                    filterSaving = true
                    coroutineScope.launch {
                        val saved = onApplyIncludedSources(filterDraft)
                        filterSaving = false
                        if (saved) dialog = null else snackbar.showSnackbar("查看范围保存失败，仍使用原筛选")
                    }
                }
            },
            onDismiss = { if (!filterSaving) dialog = null },
        )
        SourceDialog.EXCLUDE -> SourceSelectionDialog(
            title = "不监控这些应用（所有资料）",
            sources = exclusionChoices,
            checkedSources = exclusionChoices
                .asSequence()
                .map(SourceChoice::source)
                .filter { source -> exclusions.any { it.sourcePackage == source.sourcePackage } }
                .toSet(),
            emptyMeansAll = false,
            supportingText = "受 Android 限制，应用列表可能不完整；未列出的应用仍会默认记录，首次形成历史后即可在这里排除。当前排除按同包的所有资料生效。",
            onToggle = { source, excluded ->
                coroutineScope.launch {
                    if (!onSetSourceExcluded(source.sourcePackage, excluded)) {
                        snackbar.showSnackbar("未能保存设置，监控范围没有改变")
                    }
                }
            },
            onClear = null,
            onConfirm = { dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun InboxHeader(
    state: AppUiState,
    selectedCount: Int,
    onOpenFilters: () -> Unit,
    onOpenExclusions: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRequestStatusNotification: () -> Unit,
    onOpenStatusChannel: () -> Unit,
    rebindFeedback: String?,
    onRequestListenerRebind: () -> Unit,
) {
    val presentation = StatusPresentationDeriver.derive(
        listenerAccessGranted = state.listenerAccessGranted,
        connectionObserved = state.listenerConnectionObserved,
        listenerConnected = state.listenerConnected,
    )
    val healthy = presentation == StatusPresentationState.LISTENING
    val healthTitle = when (presentation) {
        StatusPresentationState.LISTENING -> "● 正在记录通知"
        StatusPresentationState.WAITING_FOR_CONNECTION -> "◆ 已授权，等待系统连接"
        StatusPresentationState.LISTENER_INTERRUPTED -> "◆ 监听连接已中断"
        StatusPresentationState.ACCESS_REQUIRED -> "◆ 需要通知使用权"
    }
    val listenerDetail = when (presentation) {
        StatusPresentationState.LISTENING -> "已连接"
        StatusPresentationState.WAITING_FOR_CONNECTION -> "等待连接"
        StatusPresentationState.LISTENER_INTERRUPTED -> "已中断"
        StatusPresentationState.ACCESS_REQUIRED -> "未授权"
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.inbox_eyebrow),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (healthy) {
                    ConfirmedBlue.copy(alpha = 0.12f)
                } else {
                    UnconfirmedYellow.copy(alpha = 0.18f)
                },
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = healthTitle,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "通知使用权 ${state.listenerAccessGranted.status()}　监听 $listenerDetail\n通知权限 ${state.statusNotificationGranted.status()}　状态渠道 ${state.statusNotificationChannelEnabled.status()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!state.listenerAccessGranted) {
            Button(onClick = onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_grant_notification_access))
            }
        }
        if (state.listenerAccessGranted && !state.listenerConnected) {
            Button(onClick = onRequestListenerRebind, modifier = Modifier.fillMaxWidth()) {
                Text("请求重新连接监听")
            }
            rebindFeedback?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                Text("打开通知使用权设置")
            }
        }
        if (!state.statusNotificationGranted) {
            Button(onClick = onRequestStatusNotification, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_grant_status_notification))
            }
        }
        if (state.statusNotificationGranted && !state.statusNotificationChannelEnabled) {
            Button(onClick = onOpenStatusChannel, modifier = Modifier.fillMaxWidth()) {
                Text("重新开启状态通知渠道")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onOpenFilters, modifier = Modifier.weight(1f)) {
                Text(if (selectedCount == 0) "查看：全部" else "查看：$selectedCount 个")
            }
            OutlinedButton(onClick = onOpenExclusions, modifier = Modifier.weight(1f)) {
                Text("不监控")
            }
        }
        Text(
            text = "蓝色实线：已确认运行　黄色虚线：未确认运行",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TimelineNodeCard(
    node: TimelineNode,
    confirmedToNext: Boolean?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: suspend (NotificationItemEntity) -> RuntimeActionStatus,
    hasRuntimeAction: (NotificationItemEntity) -> Boolean,
    excludedPackages: Set<String>,
    onSetSourceExcluded: suspend (NotificationItemEntity, Boolean) -> Boolean,
    onOpenSource: suspend (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HealthRail(confirmed = confirmedToNext)
        Card(modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                if (node.members.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggle)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SourceAppIcon(
                            sourcePackage = node.anchor.sourcePackage,
                            sourceName = node.anchor.sourceName(),
                            size = 36.dp,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(node.anchor.sourceName(), fontWeight = FontWeight.Bold)
                            Text(
                                "${node.members.size} 条 · ${formatTime(node.members.last().sortTimeEpochMillis)}—${formatTime(node.anchor.sortTimeEpochMillis)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(if (expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary)
                    }
                    if (expanded) {
                        node.members.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider()
                            NotificationEntry(
                                item = item,
                                onOpen = onOpen,
                                hasRuntimeAction = hasRuntimeAction,
                                sourceExcluded = item.sourcePackage in excludedPackages,
                                onSetSourceExcluded = onSetSourceExcluded,
                                onOpenSource = onOpenSource,
                            )
                        }
                    } else {
                        HorizontalDivider()
                        NotificationEntry(
                            item = node.anchor,
                            onOpen = onOpen,
                            hasRuntimeAction = hasRuntimeAction,
                            sourceExcluded = node.anchor.sourcePackage in excludedPackages,
                            onSetSourceExcluded = onSetSourceExcluded,
                            onOpenSource = onOpenSource,
                        )
                    }
                } else {
                    NotificationEntry(
                        item = node.anchor,
                        onOpen = onOpen,
                        hasRuntimeAction = hasRuntimeAction,
                        sourceExcluded = node.anchor.sourcePackage in excludedPackages,
                        onSetSourceExcluded = onSetSourceExcluded,
                        onOpenSource = onOpenSource,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationEntry(
    item: NotificationItemEntity,
    onOpen: suspend (NotificationItemEntity) -> RuntimeActionStatus,
    hasRuntimeAction: (NotificationItemEntity) -> Boolean,
    sourceExcluded: Boolean,
    onSetSourceExcluded: suspend (NotificationItemEntity, Boolean) -> Boolean,
    onOpenSource: suspend (String) -> Unit,
) {
    var launch by remember { mutableStateOf(false) }
    var openSource by remember { mutableStateOf(false) }
    var showFallbackDialog by remember { mutableStateOf(false) }
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var showExclusionDialog by remember { mutableStateOf(false) }
    var requestedExcluded by remember { mutableStateOf(true) }
    var exclusionSaving by remember { mutableStateOf(false) }
    var exclusionError by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var runtimeAvailable by remember(item.itemId, item.lastUpdatedAtEpochMillis) {
        mutableStateOf(hasRuntimeAction(item))
    }
    if (launch) {
        LaunchedEffect(item.itemId) {
            val status = onOpen(item)
            if (status != RuntimeActionStatus.ACCEPTED) runtimeAvailable = false
            launch = false
        }
    }
    if (openSource) {
        LaunchedEffect(item.sourcePackage) {
            onOpenSource(item.sourcePackage)
            openSource = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = runtimeAvailable) { launch = true }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceAppIcon(
                    sourcePackage = item.sourcePackage,
                    sourceName = item.sourceName(),
                    size = 24.dp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(item.sourceName(), fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(item.sortTimeEpochMillis),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Box {
                    IconButton(
                        onClick = { actionMenuExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = "${item.sourceName()} 通知操作"
                        },
                    ) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(
                        expanded = actionMenuExpanded,
                        onDismissRequest = { actionMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (sourceExcluded) {
                                        "重新记录这个 App"
                                    } else {
                                        "不要再记录这个 App"
                                    },
                                )
                            },
                            onClick = {
                                actionMenuExpanded = false
                                exclusionError = false
                                requestedExcluded = !sourceExcluded
                                showExclusionDialog = true
                            },
                        )
                    }
                }
            }
        }
        item.title?.let { title ->
            Text(
                title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item.body?.let { body ->
            Text(
                body,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = when {
                runtimeAvailable -> "点按打开原内容"
                item.hadContentIntent -> "精确跳转已不在当前进程中"
                else -> "原通知没有提供精确跳转"
            },
            color = if (runtimeAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelSmall,
        )
        if (!runtimeAvailable) {
            TextButton(onClick = { showFallbackDialog = true }) {
                Text("查看跳转选项")
            }
        }
    }
    if (showFallbackDialog) {
        AlertDialog(
            onDismissRequest = { showFallbackDialog = false },
            title = { Text("无法精确打开原内容") },
            text = {
                Text(
                    if (item.hadContentIntent) {
                        "原通知曾提供跳转，但它只存在于当时的 OMN 进程中。归档记录仍会保留。"
                    } else {
                        "原通知没有提供可用的精确跳转。归档记录仍会保留。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFallbackDialog = false
                        openSource = true
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "仅打开来源应用，可能不会到达原内容"
                    },
                ) {
                    Text("仅打开来源应用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFallbackDialog = false }) { Text("取消") }
            },
        )
    }
    if (showExclusionDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!exclusionSaving) showExclusionDialog = false
            },
            title = {
                Text(
                    if (requestedExcluded) {
                        "不要再记录 ${item.sourceName()}？"
                    } else {
                        "重新记录 ${item.sourceName()}？"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (requestedExcluded) {
                            "之后不再记录这个 App 的新通知；已有记录会保留。当前版本对同一 App 的所有资料生效。"
                        } else {
                            "之后会重新记录这个 App 的新通知；排除期间的通知无法补回。当前版本对同一 App 的所有资料生效。"
                        },
                    )
                    if (exclusionError) {
                        Text(
                            "设置没有保存，请重试。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !exclusionSaving,
                    onClick = {
                        exclusionSaving = true
                        exclusionError = false
                        coroutineScope.launch {
                            val saved = onSetSourceExcluded(item, requestedExcluded)
                            exclusionSaving = false
                            exclusionError = !saved
                            if (saved) showExclusionDialog = false
                        }
                    },
                ) {
                    Text(
                        when {
                            exclusionSaving -> "保存中…"
                            requestedExcluded -> "不再记录"
                            else -> "重新记录"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !exclusionSaving,
                    onClick = { showExclusionDialog = false },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun HealthRail(confirmed: Boolean?) {
    val text = when (confirmed) {
        true -> "蓝色实线：记录时已确认运行"
        false -> "黄色虚线：该段未确认运行"
        null -> "当前已加载时间轴的边界；不推断更早区间"
    }
    Column(
        modifier = Modifier
            .width(18.dp)
            .fillMaxHeight()
            .semantics { contentDescription = text },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(12.dp),
            color = ConfirmedBlue,
            shape = CircleShape,
        ) {}
        Canvas(Modifier.weight(1f).width(4.dp)) {
            if (confirmed != null) {
                drawLine(
                    color = if (confirmed) ConfirmedBlue else UnconfirmedYellow,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = size.width,
                    pathEffect = if (confirmed) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
        }
    }
}

@Composable
private fun SourceAppIcon(
    sourcePackage: String,
    sourceName: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, sourcePackage) {
        value = SourceIconCache.load(context, sourcePackage)
    }
    val semanticsModifier = modifier
        .size(size)
        .semantics { contentDescription = "$sourceName 应用图标" }
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = semanticsModifier,
        )
    } else {
        Surface(
            modifier = semanticsModifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    sourceName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyInbox() {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.inbox_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.inbox_empty_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SourceSelectionDialog(
    title: String,
    sources: List<SourceChoice>,
    checkedSources: Set<AppUserKey>,
    emptyMeansAll: Boolean,
    supportingText: String?,
    onToggle: (AppUserKey, Boolean) -> Unit,
    onClear: (() -> Unit)?,
    confirmLabel: String = "完成",
    confirmEnabled: Boolean = true,
    showCancel: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                supportingText?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (sources.isEmpty()) {
                    Text("收到第一条通知后，来源应用会出现在这里。")
                } else {
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        items(sources, key = { source -> sourceDialogSaveableItemKey(source.source) }) { source ->
                            val checked = source.source in checkedSources
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(source.source, !checked) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggle(source.source, it) },
                                )
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(
                                        if (source.profileLabel == null) source.sourceLabel
                                        else "${source.sourceLabel} · ${source.profileLabel}",
                                    )
                                    Text(
                                        "${source.recordCount} 条 · ${source.source.sourcePackage}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
        },
        dismissButton = if ((emptyMeansAll && onClear != null) || showCancel) {
            {
                Row {
                    if (emptyMeansAll && onClear != null) {
                        TextButton(onClick = onClear, enabled = confirmEnabled) { Text("查看全部") }
                    }
                    if (showCancel) {
                        TextButton(onClick = onDismiss, enabled = confirmEnabled) { Text("取消") }
                    }
                }
            }
        } else {
            null
        },
    )
}

private data class TimelineNode(val members: List<NotificationItemEntity>) {
    val anchor: NotificationItemEntity get() = members.first()
    val key: Long get() = anchor.itemId
}

private data class SourceChoice(
    val source: AppUserKey,
    val sourceLabel: String,
    val profileLabel: String?,
    val recordCount: Long,
)

private fun groupTimeline(items: List<NotificationItemEntity>): List<TimelineNode> {
    val byId = items.associateBy(NotificationItemEntity::itemId)
    val candidates = items.map { item ->
        TimelineGroupingCandidate(
            itemId = item.itemId,
            sourcePackage = item.sourcePackage,
            sourceUserRef = item.sourceUserRef,
            firstReceivedAtEpochMillis = item.firstReceivedAtEpochMillis,
        )
    }
    return TimelineGrouper.group(candidates).map { group ->
        TimelineNode(group.memberItemIds.map { itemId -> requireNotNull(byId[itemId]) })
    }
}

private fun NotificationItemEntity.sourceName(): String = sourceLabelSnapshot ?: sourcePackage

internal fun sourceDialogSaveableItemKey(source: AppUserKey): String =
    InboxSourceKeyCodec.encode(source)

private fun Boolean.status(): String = if (this) "正常" else "不可用"

private fun ListenerRebindResult.userMessage(): String = when (this) {
    ListenerRebindResult.REQUESTED -> "已请求系统连接；状态变为“正在记录通知”才算成功"
    ListenerRebindResult.ALREADY_CONNECTED -> "监听已经连接"
    ListenerRebindResult.ACCESS_REQUIRED -> "请先授予通知使用权"
    ListenerRebindResult.COOLDOWN_OR_AUTOMATIC_LIMIT -> "操作太快，请稍等一秒再试"
    ListenerRebindResult.PLATFORM_REJECTED -> "系统未接受重连请求"
}

private fun formatTime(epochMillis: Long): String = TIME_FORMATTER.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private enum class SourceDialog { FILTER, EXCLUDE }

private object SourceIconCache {
    private val icons = LruCache<String, ImageBitmap>(64)

    suspend fun load(context: Context, sourcePackage: String): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(icons) { icons.get(sourcePackage) }?.let { return@withContext it }
        val bitmap = runCatching {
            val drawable = context.packageManager.getApplicationIcon(sourcePackage)
                .constantState
                ?.newDrawable()
                ?.mutate()
                ?: context.packageManager.getApplicationIcon(sourcePackage).mutate()
            val width = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(192)
            val height = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(192)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { output ->
                drawable.setBounds(0, 0, width, height)
                drawable.draw(AndroidCanvas(output))
            }.asImageBitmap()
        }.getOrNull() ?: return@withContext null
        synchronized(icons) { icons.put(sourcePackage, bitmap) }
        bitmap
    }
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private const val GROUP_WINDOW_MILLIS = TimelineGrouper.WINDOW_MILLIS
private val ConfirmedBlue = Color(0xFF1565C0)
private val UnconfirmedYellow = Color(0xFFF9A825)
