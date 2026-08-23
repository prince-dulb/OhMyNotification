# P0-101 / SP-01 真实通知字段与生命周期采集

状态：`[候选] IMPLEMENTED / DEVICE_NOT_RUN`

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
3. 先用可控测试源执行发布、更新和移除；
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
