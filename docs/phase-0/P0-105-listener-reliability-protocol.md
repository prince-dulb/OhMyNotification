# P0-105 / SP-05 监听可靠性与红魔后台策略协议

状态：`[候选] IN_PROGRESS / USER_REBIND_REQUIRED`

建立时间：2026-08-23（Asia/Shanghai）

## 1. 目的

本协议在红魔 11 Pro+ / Android 16 上记录通知监听器的授权、实际绑定、进程、系统回收优先级、待机状态和开机代次，并与 debug 监听器私有事件流对账。目标是确定真实漏记边界和必要的用户设置，不以“进程还在”或“授权名单存在”替代回调证据。

## 2. 只读检查点

`capture-phase0-listener-state.ps1` 每次生成一份 `.local-evidence/phase-0/` 私有 JSON，包含：

- 主机时间、设备开机单调时间和开机代次哈希；
- OMN 是否安装、是否 stopped、是否有进程及 `oom_score_adj`；
- 通知使用权与监听服务实际绑定是否分别成立；
- `POST_NOTIFICATIONS`、后台运行 AppOp、待机桶和电池优化豁免状态；
- 屏幕唤醒状态和深度 Doze 状态。

工具不修改通知使用权、电池策略、待机桶或其他系统设置，不读取通知正文，不保存完整授权名单或安装应用列表。普通终端输出只显示布尔状态和枚举摘要。

```powershell
.\tools\capture-phase0-listener-state.ps1 -SelfTest
.\tools\capture-phase0-listener-state.ps1 -Checkpoint baseline -RunId listener-baseline-01
.\tools\request-phase0-listener-rebind.ps1 -ResetBinding
```

`-ResetBinding` 只调用 Android 34+ 提供的 `requestUnbind()` 与 `requestRebind()`，不改变通知使用权。它仅用于验证宿主处于“已授权但未绑定”状态时能否恢复连接，不得做成无界循环。

## 3. 真机矩阵

每组实验使用同一 `RunId`，在动作前后分别采集检查点，并拉取监听器私有事件流：

| 场景 | 前置检查点 | 动作 | 后置检查点 | 必须核对 |
|---|---|---|---|---|
| 首次授权 | `access-disabled` | 用户在系统页授予通知使用权 | `access-enabled` | 授权与实际绑定的时间差、连接和补扫事件 |
| 主动撤权/恢复 | `before-access-toggle` | 用户关闭再开启 | `after-access-toggle` | 断连、重连、黄色窗口与补扫边界 |
| 系统终止进程 | `before-process-kill` | 只使用计划中允许的可恢复测试动作 | `after-process-kill`、`after-recovery` | 是否自动重绑、期间通知是否由补扫恢复 |
| 长锁屏 | `before-lock` | 息屏并保持固定等待窗口 | `after-lock` | Doze、进程/绑定、回调和遗漏窗口 |
| 设备重启 | `before-reboot` | 用户执行重启 | `after-reboot`、`after-unlock` | 新开机代次、解锁前后绑定和补扫 |
| 厂商后台限制 | `before-policy-change` | 用户在红魔界面切换一个具名设置 | `after-policy-change` | 设置是否必要及它对绑定、回调、资源的影响 |

系统终止、重启和厂商设置变更均为外部状态变化，执行前仍遵循用户红线；本协议和脚本不会自动执行这些动作。

## 4. 当前边界

当前手机 ADB 在线、应用已安装。用户关闭再开启通知使用权后，检查点观察到 `access=true / bound=true / process=true`，监听器随即写入连接与活动通知补扫事件。

随后运行 instrumentation 合同时，Runner 强停产品进程，检查点变为 `access=true / bound=false / process=false`。启动应用并调用 Android `NotificationListenerService.requestRebind()` 后，进程恢复，但红魔在 10 秒观察窗口内仍保持 `bound=false`。2026-08-23 又在 Android 16 真机上验证了一次有界 `requestUnbind()` → 250 ms → `requestRebind()`；系统接受请求，5 秒内仍为 `access=true / bound=false / process=true`，私有检查点为 `listener-recovery-01 / after-reset-binding`。因此普通和成对请求都不能在这台设备上充当强制恢复手段。该行为来自 instrumentation/覆盖安装造成的人工强停路径，不得直接外推为普通系统回收结论。

覆盖安装 debug APK 会强停目标包并断开已工作的监听器。后续受控回调实验必须先完成全部需要安装的新代码，再由用户最后一次关闭并开启通知使用权；连接恢复后不得再运行 `adb install`、instrumentation 或其他会强停产品包的动作，只运行独立测试 APK 的直驱入口与只读证据工具。

受控源已增加不强停产品进程的 `-Direct` 合同并验证四步自身行为通过。重新授权后的下一步是：先采集 `before-direct-contract`，运行直驱合同，再核对监听事件恰为同身份发布、更新和移除。P0-105 的锁屏、普通系统回收、重启及厂商策略矩阵尚未运行，不能给出 `GO`。
