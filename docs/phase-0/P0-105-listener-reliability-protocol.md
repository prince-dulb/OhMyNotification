# P0-105 / SP-05 监听可靠性与红魔后台策略协议

状态：`[候选] IN_PROGRESS / CONTROLLED_RECOVERY_OBSERVED`

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

`-ResetBinding` 只调用 Android 34+ 提供的 `requestUnbind()` 与 `requestRebind()`，不改变通知使用权。它仅用于复现实验，不得进入产品恢复路径或做成无界循环；红魔实测 250 ms 配对调用会产生下面记录的时序竞争。

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

随后运行 instrumentation 合同时，Runner 强停产品进程，检查点变为 `access=true / bound=false / process=false`。启动应用并调用 Android `NotificationListenerService.requestRebind()` 后，进程恢复，但红魔在最初 10 秒观察窗口内仍保持 `bound=false`。2026-08-23 又验证了一次 `requestUnbind()` → 250 ms → `requestRebind()`；系统接受请求，5 秒内仍为 `access=true / bound=false / process=true`，且 `dumpsys notification` 明确把该组件列入 `Snoozed notification listeners`。这说明 250 ms 配对调用在此 ROM 上存在时序竞争，不得作为产品恢复算法。

待解绑状态稳定后再单独调用一次 `requestRebind()`，组件从休眠名单恢复，检查点 `listener-recovery-02 / after-delayed-rebind` 为 `access=true / bound=true / process=true`。之后覆盖安装带 Android 16 动作修复的新 debug APK，等待 3 秒再单次请求重绑，也在 5 秒内恢复为三项全真。由此可确认：单次、有界、在应用自然启动或明确恢复入口触发的延迟重绑是当前候选；不能声称它覆盖普通系统回收、重启或长待机。

覆盖安装 debug APK 会强停目标包并断开已工作的监听器。真机可靠性测量仍不得在测量窗口内运行 `adb install`、instrumentation 或其他会强停产品包的动作；调试恢复只能作为人工边界单独记录。

受控源的 `-Direct` 链路已由监听器取得同身份发布、更新和移除三回调，并在原通知移除后完成运行时动作真实落页；前后连接检查点均为真。P0-105 的锁屏、普通系统回收、重启及厂商策略矩阵尚未运行，不能给出 `GO`。
