# P0-102 / SP-02 原始动作寿命与持有成本

状态：`[候选] CONTROLLED_AND_ONE_REAL_REMOVED_ACTION_PASSED / LONGEVITY_MATRIX_IN_PROGRESS`

建立时间：2026-08-23（Asia/Shanghai）

## 1. 目的与安全边界

本 Spike 验证通知原始 `PendingIntent` 在原通知仍在、划除、等待、来源状态变化和 OMN 进程重建边界上的实际寿命，并分别记录“系统接受派发”和“预期目标实际打开”。任何等待检查点都只是测量时间，不形成通知年龄 TTL。

debug 监听器只在当前进程内有界持有动作引用，不序列化到文件、JSONL、数据库或导出。新增的受控调度入口硬编码只选择创建方为 `io.github.prince_dulb.ohmynotification.test` 的最新句柄，不能通过 ADB 参数改成真实应用包，因此不会误触发用户真实通知动作。release APK 必须通过边界扫描证明不含该入口。

## 2. 已实现测试链路

1. 使用 `invoke-controlled-notification.ps1 -Operation publish -Direct` 发布受控通知；
2. debug 监听器捕获其 `contentIntent` 并存入当前进程的 `RuntimeActionRegistry`；
3. 可选择先从系统通知栏移除受控通知；
4. `invoke-phase0-controlled-runtime-action.ps1` 请求 OMN 发送最新受控句柄；
5. 工具读取 OMN 私有派发结果和测试 APK 私有落页回执，以新的设备单调时间区分旧回执；
6. 终端只输出 `ACCEPTED/CANCELED/NOT_FOUND`、是否实际落页和句柄数，不输出事件 ID、动作令牌或目标 payload。

受控目标回执包含测试 case、合成 token、UTC 时间和设备单调时间，只存在测试 APK 私有目录，不进入 Git。

## 3. 待运行矩阵

| 场景 | 预期可分类结果 |
|---|---|
| 原通知仍在，立即发送 | `ACCEPTED` 且目标回执前进 |
| 系统移除原通知后，OMN 进程仍存活 | 实测 `ACCEPTED` 或 `CANCELED`，不得预判 |
| 同一句柄重复发送 | 区分可重用与一次性动作 |
| OMN Activity 重建、进程不变 | 句柄仍由进程级仓储持有 |
| OMN 进程重建 | `NOT_FOUND`；不得从磁盘恢复 `PendingIntent` |
| 固定等待检查点 | 记录实际状态，不因年龄主动判失效 |
| 受控来源进入 stopped / 更新 | 区分系统接受派发与目标实际落页 |
| 句柄数量梯度 | 测量 PSS、Binder 证据和成功率，不先锁定容量 |

## 4. Android 16 发送方边界

首轮真机执行中，裸 `PendingIntent.send()` 返回 `ACCEPTED`，但受控目标没有产生新落地回执。[Android 后台 Activity 启动官方指南](https://developer.android.com/guide/components/activities/background-starts#senders-must)对目标 SDK 34+ 的发送方要求显式选择后台 Activity 启动模式；官方建议用户正在查看发送方应用时使用 `ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`。

调试入口改为可见 Activity，并在 `onPostResume()` 后使用该模式发送。同一 `action-lifecycle-01` 中，受控通知发布、同身份更新、从系统通知栏移除后，OMN 保存的运行时句柄得到：

```text
dispatch=ACCEPTED targetOpened=True
```

目标回执的设备单调时间确实前进，不是沿用旧文件。监听连接全过程保持成立。因此，受控来源已证明“原通知移除后、OMN 当前进程仍存活”的运行时句柄可以真实落页；裸 `send()` 被列为 Android 16 上的错误实现。该结论不外推到 B 站，也不证明跨等待、来源 stopped、应用更新或句柄数量梯度。

发送动作必须来自用户当前可见界面的明确点击；不得使用 `ALLOW_ALWAYS` 把后台自动拉起外部 Activity 伪装成通知跳转。

## 5. 一条真实 B 站通知的用户验收

2026-08-23，用户在红魔 11 Pro+ / Android 16 上确认：先从系统通知栏划除一条真实 B 站通知，再点击 OMN 中对应的归档条目，成功精确进入预期目标页面。紧随其后的去敏设备计数为 42 个归档条目、42 个当前进程动作句柄、38 个已移除条目，重复身份/生命周期组为 0；未读取或写入真实标题、内容 ID、URI 或动作数据。

这条通知尚未由用户分类为投稿、动态或开播，因此只记为“一条真实 B 站通知通过”，不得外推为三类全部通过。该成功发生在同一 OMN 进程生命周期内；随后覆盖安装后，运行时仓储只从 4 条仍活动的通知恢复 4 个动作句柄，已经划除的动作没有跨进程恢复。这与当前“不序列化 `PendingIntent`”的边界一致，也说明跨进程精确跳转仍需 P0-103 的持久备用目标或明确的不可恢复提示，不能把当前结果描述成永久有效。
