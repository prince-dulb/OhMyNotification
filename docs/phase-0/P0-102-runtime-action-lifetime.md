# P0-102 / SP-02 原始动作寿命与持有成本

状态：`[候选] HARNESS_IMPLEMENTED / CONTROLLED_CALLBACK_REQUIRED`

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

当前监听服务尚待用户重新切换通知使用权以恢复绑定，因此受控句柄还没有进入 registry。本文件只证明测试链路已实现并通过构建，不能把任何动作寿命场景标记为通过。
