# P0-101 / SP-01 真实通知字段与生命周期采集

状态：`[候选] DEVICE_PIPELINE_PARTIAL / CONTROLLED_CALLBACK_NOT_YET_CAPTURED`

## 1. 目的与边界

本 Spike 只回答真实通知提供哪些身份、可见字段、extras 形状、动作能力以及发布/更新/移除序列。它不宣称通知已经可靠持久化，也不把私有原始采集格式当作生产数据库 schema。

采集服务只存在于 `src/debug`。release APK 必须通过边界扫描证明不包含 `PhaseZeroNotificationListener` 或 `notification-events-v1.jsonl` 标记。

## 2. Android 官方约束

- [`NotificationListenerService`](https://developer.android.com/reference/android/service/notification/NotificationListenerService) 必须以 `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` 保护并声明 `android.service.notification.NotificationListenerService` action；当前官方示例使用 `android:exported="false"`。
- 服务只能在 `onListenerConnected()` 后执行活动通知读取；重连补扫不能推断已经消失的通知。
- [`Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS`](https://developer.android.com/reference/android/provider/Settings#ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS) 配合 `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` 打开应用级授权页；ROM 不支持时回退到监听器列表。

## 3. 私有采集格式

debug 服务把 JSONL 写入应用私有目录 `files/phase0/notification-events-v1.jsonl`，上限 16 MiB，包含：

- 进程会话、监听连接和单调时间；
- 来源包、用户引用、系统 key、ID、tag、发布时间和分组身份；
- 标准可见文本字段及逐字段复制失败类型；
- extras 的 key、运行时类型和受控标量投影；
- 内容、删除、全屏和通知 action 的能力摘要；
- 发布/更新、移除原因和重连活动通知快照；
- 当前进程内有界的原始内容动作引用数量。

OMN 自身通知在读取正文、extras 和动作前排除。原始 JSONL、通知内容和动作相关私有值不进入 Git，也不写入普通日志。

## 4. 真机执行

1. 构建并安装 debug APK；
2. 运行 `.\tools\open-notification-listener-settings.ps1`，由用户在系统界面授予通知使用权；
3. 先用 `-Direct` 可控测试源执行发布、更新和移除；不得用会强停产品进程的 instrumentation 合同替代监听回调证据；
4. 等待 B 站投稿、动态和开播真实通知，记录用户核对的类型；
5. 运行 `.\tools\pull-phase0-notification-events.ps1`，把字节流直接拉入 `.local-evidence/phase-0/`；
6. 将本次出现的标题、正文、内容 ID、动作相关值、原始序列号和私有路径登记到对应 `.sentinels.txt`；
7. 只把字段存在性、类型、计数、缺失矩阵和去标识生命周期结论写入公开文档。

## 5. GO 条件

- 可控源的发布、更新、移除和内部落页通过；
- B 站投稿、动态、开播三类各有真实样本，未观察到的类型保持 `NOT_OBSERVED`；
- 身份字段能提出不依赖标题或正文的代次候选，并有 key/ID/tag 复用反例；
- debug 采集文件可拉取、逐行解析、关联 commit/设备/样本类型，且公开树敏感扫描通过；
- release APK 不含采集服务、私有格式标记或测试组件。

## 6. 红魔首次设备结果

2026-08-23，用户在红魔 11 Pro+ / Android 16 上重新开启通知使用权后，debug 监听器实际绑定并成功把私有事件流拉取到 `.local-evidence/phase-0/`。本次文件共 100 行、251,010 字节，SHA-256 为：

```text
52e20d557dfe593b4c254bc839d23008efeea0e9bb1f63872e307bd71287c584
```

去内容聚合结果：

| 事件 | 数量 |
|---|---:|
| `PROCESS_CREATED` | 1 |
| `LISTENER_CONNECTED` | 1 |
| `RECOVERY_SNAPSHOT_COMPLETED` | 1，活动通知计数 34 |
| `RECOVERY_SNAPSHOT` | 34 |
| `POST_OR_UPDATE` | 63 |

97 条通知回调均形成 `CAPTURED_PRIVATE_PHASE0`；34 条补扫样本全部带 `contentIntent` 能力摘要，其中 3 条存在 action 数组。此公开结果只记录计数与能力存在性，不包含来源包、标题、正文、系统 key、动作创建方或其他私有值。

首次受控回调尝试发现：instrumentation Runner 会在运行合同前强停产品进程，因此受控源自身合同虽然通过，监听器当时已经不存在，不能把该结果写成“监听器收到受控通知”。工具现已增加 `-Direct` 模式，直接驱动测试 APK 的命令 Activity；发布、更新、真实内部落页和移除四步均通过且不会主动停止 OMN。等待监听器再次绑定后仍须重跑并取得受控来源的三条回调序列。

此外，私有拉取脚本已改为兼容 Windows PowerShell 5.1 的 `ProcessStartInfo.Arguments` 路径，实机拉取通过。B 站三类真实样本、身份复用反例和受控回调仍未完成，因此本 Spike 保持部分通过而不是 `GO`。
