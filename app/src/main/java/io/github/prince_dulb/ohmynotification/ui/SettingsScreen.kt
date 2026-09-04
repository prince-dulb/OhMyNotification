package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.prince_dulb.ohmynotification.core.model.AppSettingsSnapshot
import io.github.prince_dulb.ohmynotification.core.model.GroupingWindowOptions
import io.github.prince_dulb.ohmynotification.core.model.NotificationType
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    settings: AppSettingsSnapshot,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    unavailableArchiveCount: Long,
    onOpenUnavailableArchive: () -> Unit,
    onManageExcludedApps: () -> Unit,
    onSetNotificationTypeRecorded: suspend (NotificationType, Boolean) -> Boolean,
    onSetGroupingWindowMinutes: suspend (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "settings-header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onBack) { Text("返回通知记录") }
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            item(key = "notification-types") {
                SettingsCard(
                    title = "记录哪些通知",
                    supportingText = "只影响之后收到的通知，已有记录会保留。类型按通知声明的信息识别，识别不到的归入“其他通知”。",
                ) {
                    NotificationType.entries.forEach { type ->
                        val copy = type.displayCopy()
                        NotificationTypeRow(
                            title = copy.title,
                            description = copy.description,
                            checked = type in settings.recordedNotificationTypes,
                            onCheckedChange = { checked ->
                                coroutineScope.launch {
                                    if (!onSetNotificationTypeRecorded(type, checked)) {
                                        snackbarHostState.showSnackbar("未能保存通知类型设置")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item(key = "unavailable-archive") {
                SettingsCard(
                    title = "当前无跳转动作归档",
                    supportingText = "记录仍保存在本机。点按其中一条时，OMN 会重扫一次系统当前仍存在的通知；若找回同一条的动作就尝试打开，但不保证成功。",
                ) {
                    OutlinedButton(
                        onClick = onOpenUnavailableArchive,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看 $unavailableArchiveCount 条归档")
                    }
                }
            }

            item(key = "grouping-window") {
                SettingsCard(
                    title = "跨应用合并时间间隔",
                    supportingText = "同一应用连续出现、且中间没有其他应用通知时仍会合并；这里控制被其他应用打断后，隔多久还能合回同一抽屉。",
                ) {
                    GroupingWindowOptions.minutes.chunked(2).forEach { rowOptions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowOptions.forEach { minutes ->
                                FilterChip(
                                    selected = settings.groupingWindowMinutes == minutes,
                                    onClick = {
                                        coroutineScope.launch {
                                            if (!onSetGroupingWindowMinutes(minutes)) {
                                                snackbarHostState.showSnackbar("未能保存合并时间间隔")
                                            }
                                        }
                                    },
                                    label = { Text(groupingWindowLabel(minutes)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "excluded-apps") {
                SettingsCard(
                    title = "不监控的应用",
                    supportingText = "排除后不再记录该应用的新通知，已有记录不会删除。排除规则对同一应用的所有资料生效。",
                ) {
                    OutlinedButton(
                        onClick = onManageExcludedApps,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("管理不监控的应用")
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    supportingText: String,
    content: @Composable () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun NotificationTypeRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private data class NotificationTypeCopy(
    val title: String,
    val description: String,
)

private fun NotificationType.displayCopy(): NotificationTypeCopy = when (this) {
    NotificationType.MEDIA -> NotificationTypeCopy("媒体播放控件", "音乐和视频的播放、暂停、进度等控件")
    NotificationType.COMMUNICATION -> NotificationTypeCopy("消息与通话", "聊天消息、来电、邮件和社交通知")
    NotificationType.ALERT_OR_NAVIGATION -> NotificationTypeCopy("闹钟、提醒与导航", "闹钟、日程提醒、导航和运动状态")
    NotificationType.ONGOING_OR_PROGRESS -> NotificationTypeCopy("持续运行与进度", "前台服务、下载进度和持续状态")
    NotificationType.OTHER -> NotificationTypeCopy("其他通知", "未归入以上类型的普通通知")
}

private fun groupingWindowLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes == 60 -> "1 小时"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "$minutes 分钟"
}
