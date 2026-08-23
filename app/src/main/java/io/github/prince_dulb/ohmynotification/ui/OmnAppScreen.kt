package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.prince_dulb.ohmynotification.R
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStatus
import io.github.prince_dulb.ohmynotification.data.NotificationItemEntity
import io.github.prince_dulb.ohmynotification.data.NotificationRepository
import io.github.prince_dulb.ohmynotification.data.SourceSummaryRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

data class AppUiState(
    val listenerAccessGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val statusNotificationGranted: Boolean = false,
)

@Composable
internal fun OmnAppScreen(
    state: AppUiState,
    repository: NotificationRepository,
    onOpenNotificationAccess: () -> Unit,
    onRequestStatusNotification: () -> Unit,
    onSetSourceExcluded: (String, Boolean) -> Unit,
    onOpenNotification: (NotificationItemEntity) -> RuntimeActionStatus,
    modifier: Modifier = Modifier,
) {
    val sources by repository.sourceSummaries.collectAsStateWithLifecycle(emptyList())
    val exclusions by repository.excludedSources.collectAsStateWithLifecycle(emptyList())
    var selectedSources by remember { mutableStateOf(emptySet<String>()) }
    var dialog by remember { mutableStateOf<SourceDialog?>(null) }
    val expandedDrawers = remember { mutableStateMapOf<Long, Boolean>() }
    val snackbar = remember { SnackbarHostState() }
    val pagingFlow = remember(selectedSources) { repository.pagedItems(selectedSources) }
    val pagingItems = pagingFlow.collectAsLazyPagingItems()
    val loadedItems = pagingItems.itemSnapshotList.items
    val nodes = remember(loadedItems) { groupTimeline(loadedItems) }
    val listState = rememberLazyListState()

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
                        onOpenFilters = { dialog = SourceDialog.FILTER },
                        onOpenExclusions = { dialog = SourceDialog.EXCLUDE },
                        onOpenNotificationAccess = onOpenNotificationAccess,
                        onRequestStatusNotification = onRequestStatusNotification,
                    )
                }

                if (nodes.isEmpty() && pagingItems.loadState.refresh is LoadState.NotLoading) {
                    item(key = "empty") { EmptyInbox() }
                }

                itemsIndexed(nodes, key = { _, node -> node.key }) { index, node ->
                    val next = nodes.getOrNull(index + 1)
                    val confirmedToNext = next == null ||
                        node.anchor.runtimeSessionIdAtLastCapture == next.anchor.runtimeSessionIdAtLastCapture &&
                        node.anchor.sortTimeEpochMillis - next.anchor.sortTimeEpochMillis <= GROUP_WINDOW_MILLIS
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
            sources = sources,
            checkedPackages = selectedSources,
            emptyMeansAll = true,
            onToggle = { sourcePackage, checked ->
                selectedSources = selectedSources.toMutableSet().apply {
                    if (checked) add(sourcePackage) else remove(sourcePackage)
                }
            },
            onClear = { selectedSources = emptySet() },
            onDismiss = { dialog = null },
        )
        SourceDialog.EXCLUDE -> SourceSelectionDialog(
            title = "不监控这些应用",
            sources = sources,
            checkedPackages = exclusions.mapTo(linkedSetOf()) { it.sourcePackage },
            emptyMeansAll = false,
            onToggle = onSetSourceExcluded,
            onClear = null,
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
) {
    val healthy = state.listenerAccessGranted && state.listenerConnected
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
                    text = if (healthy) "● 正在记录通知" else "◆ 尚未确认正在记录",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "通知使用权 ${state.listenerAccessGranted.status()}　监听 ${state.listenerConnected.status()}　常驻通知 ${state.statusNotificationGranted.status()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!state.listenerAccessGranted) {
            Button(onClick = onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_grant_notification_access))
            }
        }
        if (!state.statusNotificationGranted) {
            Button(onClick = onRequestStatusNotification, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_grant_status_notification))
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
    }
}

@Composable
private fun TimelineNodeCard(
    node: TimelineNode,
    confirmedToNext: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: suspend (NotificationItemEntity) -> Unit,
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
                            NotificationEntry(item = item, onOpen = onOpen)
                        }
                    } else {
                        HorizontalDivider()
                        NotificationEntry(item = node.anchor, onOpen = onOpen)
                    }
                } else {
                    NotificationEntry(item = node.anchor, onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun NotificationEntry(
    item: NotificationItemEntity,
    onOpen: suspend (NotificationItemEntity) -> Unit,
) {
    var launch by remember { mutableStateOf(false) }
    if (launch) {
        LaunchedEffect(item.itemId) {
            onOpen(item)
            launch = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.hadContentIntent) { launch = true }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(item.sourceName(), fontWeight = FontWeight.SemiBold)
            Text(
                formatTime(item.sortTimeEpochMillis),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
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
            text = if (item.hadContentIntent) "点按尝试打开原内容" else "原通知没有提供跳转",
            color = if (item.hadContentIntent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun HealthRail(confirmed: Boolean) {
    val text = if (confirmed) "蓝色实线：记录时已确认运行" else "黄色虚线：该段未确认运行"
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
    sources: List<SourceSummaryRow>,
    checkedPackages: Set<String>,
    emptyMeansAll: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (sources.isEmpty()) {
                Text("收到第一条通知后，来源应用会出现在这里。")
            } else {
                LazyColumn {
                    items(sources, key = SourceSummaryRow::sourcePackage) { source ->
                        val checked = source.sourcePackage in checkedPackages
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(source.sourcePackage, !checked) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggle(source.sourcePackage, it) },
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(source.sourceLabelSnapshot ?: source.sourcePackage)
                                Text(
                                    "${source.recordCount} 条 · ${source.sourcePackage}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = if (emptyMeansAll && onClear != null) {
            { TextButton(onClick = onClear) { Text("查看全部") } }
        } else {
            null
        },
    )
}

private data class TimelineNode(val members: List<NotificationItemEntity>) {
    val anchor: NotificationItemEntity get() = members.first()
    val key: Long get() = anchor.itemId
}

private fun groupTimeline(items: List<NotificationItemEntity>): List<TimelineNode> {
    val consumed = BooleanArray(items.size)
    val nodes = arrayListOf<TimelineNode>()
    items.indices.forEach { anchorIndex ->
        if (consumed[anchorIndex]) return@forEach
        val anchor = items[anchorIndex]
        val memberIndexes = arrayListOf(anchorIndex)
        var otherApps = 0
        var scan = anchorIndex + 1
        while (scan < items.size) {
            val candidate = items[scan]
            if (anchor.sortTimeEpochMillis - candidate.sortTimeEpochMillis > GROUP_WINDOW_MILLIS) break
            if (candidate.sourcePackage == anchor.sourcePackage &&
                candidate.sourceUserRef == anchor.sourceUserRef &&
                !consumed[scan]
            ) {
                memberIndexes += scan
            } else {
                if (otherApps == MAX_INTERVENING_OTHER_ITEMS) break
                otherApps += 1
            }
            scan += 1
        }
        if (memberIndexes.size > 1) {
            memberIndexes.forEach { memberIndex -> consumed[memberIndex] = true }
        } else {
            consumed[anchorIndex] = true
        }
        nodes += TimelineNode(memberIndexes.map(items::get))
    }
    return nodes
}

private fun NotificationItemEntity.sourceName(): String = sourceLabelSnapshot ?: sourcePackage

private fun Boolean.status(): String = if (this) "正常" else "不可用"

private fun formatTime(epochMillis: Long): String = TIME_FORMATTER.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private enum class SourceDialog { FILTER, EXCLUDE }

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private const val GROUP_WINDOW_MILLIS = 15L * 60L * 1_000L
private const val MAX_INTERVENING_OTHER_ITEMS = 3
private val ConfirmedBlue = Color(0xFF1565C0)
private val UnconfirmedYellow = Color(0xFFF9A825)
