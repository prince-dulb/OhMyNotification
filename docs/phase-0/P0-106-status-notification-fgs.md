# P0-106 / SP-06 状态通知与前台服务 A/B

状态：`[候选] ORDINARY_STATUS_BASELINE_RUNNING / REDMAGIC_AB_NOT_RUN`

核对时间：2026-08-23（Asia/Shanghai）

## 1. 结论先行

当前生产候选基线是：由系统绑定的 `NotificationListenerService` 在真实状态变化或提交摘要变化时直接维护一条普通 ongoing 状态通知，不额外启动前台服务。

独立前台服务不作为默认方案。它只有在 P0-105 证明普通监听拓扑存在不可接受的真实漏记，并且同负载 A/B 证明 `specialUse` FGS 带来明确可靠性收益且资源代价可接受时，才保留为用户决策候选。`dataSync`、`shortService`、`systemExempted` 或其他与实际工作不符的类型全部排除。

这只是平台合规基线，不是最终 G4 决策；红魔真机 A/B 尚未运行。

## 2. 官方平台事实

- [`NotificationListenerService`](https://developer.android.com/reference/android/service/notification/NotificationListenerService) 是通知监听的专用系统绑定组件；未声明 `default_autobind=false` 时默认自动绑定。只有 `onListenerConnected()` 后才能把监听状态视为已连接。
- Android 官方要求仅在执行用户可感知、持续进行的任务时使用[前台服务](https://developer.android.com/develop/background-work/services/fgs)；低重要度工作应选择其他机制。OMN 已有专用监听组件，因此“想让进程更难被杀”本身不是充分的 FGS 用例。
- target API 34 及以上的 FGS 必须声明真实类型和对应权限。当前类型列表中只有 [`specialUse`](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use) 可能容纳未被其他类型覆盖的有效用例，并要求 `FOREGROUND_SERVICE_SPECIAL_USE`、服务类型和具名 subtype property；若进入 Google Play，该用途还会被审核。
- Android 12 及以上通常禁止从后台启动 FGS，只有[官方列出的例外](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)可用。通知监听回调本身不被本项目预先当成启动豁免；A/B 候选先从用户可见 Activity 显式启动。
- `POST_NOTIFICATIONS` 被拒绝时，FGS 可以启动，但其通知只出现在系统 Task Manager，不出现在通知抽屉；因此 FGS 不能绕过用户对“通知栏常驻可见”的授权要求。普通状态通知同样不可见。
- Android 13 及以上允许用户从 [Task Manager 停止 FGS 应用](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)。系统会移除整个应用进程、Activity 返回栈和 FGS 通知，所以 FGS 也不是不可终止的健康证明。
- 普通 [`setOngoing(true)`](https://developer.android.com/reference/android/app/Notification.Builder#setOngoing(boolean)) 通知在锁定设备上不能被用户或监听器划除；在解锁设备上，只有通话、设备管理、媒体等部分类型保证不可划除。OMN 的普通 ongoing 是否可划除必须以红魔实测为准。

## 3. 两个候选拓扑

### A. 监听器直接维护普通 ongoing 通知

```text
Android NotificationManager
  → system-bound NotificationListenerService
      → capture / persist
      → coalesced status projection
      → ordinary ongoing notification
```

优势：没有第二个长期组件、没有 FGS 权限和启动限制、空闲时天然事件驱动，最符合最低资源占用。限制：不提高进程重要性；通知可能在进程结束后残留，也可能在解锁状态被用户划除，因此只能表达“最近状态”，不能表达实时存活。

### B. 用户启动的 `specialUse` FGS 承载状态通知

```text
visible Activity user action
  → specialUse foreground service
      → one foreground notification
system-bound NotificationListenerService
  → capture / persist
  → status projection to FGS
```

候选 subtype 只能如实描述“用户明确启用的本地通知归档状态承载”，不得写成 `dataSync` 或其他虚假类型。FGS 内部仍禁止空转、轮询、心跳、唤醒锁和周期写盘。它的通知与普通方案使用同一固定 ID、同一文案与同一刷新合并规则，避免出现两条常驻通知。

## 4. 已排除方案

| 方案 | 结论 | 原因 |
|---|---|---|
| 把 NLS 本身伪装为任意 FGS 类型 | 排除 | 系统绑定生命周期与 started FGS 是不同契约；类型必须对应实际工作 |
| `dataSync` | 排除 | OMN 默认无网络，通知归档不是数据传输 |
| `shortService` | 排除 | 三分钟时限不适合用户持续开启的状态承载 |
| `systemExempted` | 排除 | 普通侧载应用不满足系统豁免条件 |
| 无类型 FGS | 排除 | target API 36 下不合规并会抛出类型异常 |
| 为保持蓝条连续而运行心跳 | 排除 | 违反性能硬约束，且把“进程活动”伪装成“监听已确认” |
| 用 FGS 绕过通知权限 | 排除 | 权限拒绝时通知抽屉仍不可见 |

## 5. 红魔 A/B 协议

只有 P0-105 的普通拓扑基线完成后才运行 B 方案，且两组必须保持：同一 APK 代码、同一通知渠道/内容、同一授权与厂商设置、同一受控负载、同一锁屏时长。

必须逐项记录：

1. 授权、实际监听绑定和重连时间；
2. 受控通知发布/更新/移除的回调完整性；
3. 进程 `oom_score_adj`、PSS、CPU 时间和写盘；
4. 应用可归因唤醒、闹钟、Job 和状态通知刷新次数；
5. 息屏/Doze、系统回收和重启后的行为；
6. 状态通知在解锁、锁屏、权限拒绝、渠道关闭和用户划除后的可见/残留语义；
7. Task Manager 停止 FGS 后的进程、通知和监听恢复边界；
8. 是否新增任何真实漏记改善，以及改善是否足以抵消常驻资源与平台复杂度。

通过门不是“FGS 进程更常驻”，而是它在相同正确性条件下显著减少不可恢复漏记，并满足最终锁定的绝对资源预算。若两者正确性相同或证据不明显，选择普通 ongoing 方案。

## 6. 当前真机基线

用户已在应用内授予 `POST_NOTIFICATIONS`，固定 `running_status` 渠道启用，普通 ongoing 状态卡已在目标设备通知栏实际显示。连续三次 Activity 刷新时，语义模型未变化，发布计数保持不变，证明普通刷新不会无条件调用 `notify()`。

随后真机发现一个普通状态卡恢复缺口：监听器仍连接、权限和渠道均启用，但状态卡已不在系统活动通知列表；Activity 刷新因语义签名未变化而跳过补发。生产实现现改为“语义签名相同且固定通知仍活跃”时才跳过。Debug 定向取消固定状态卡后，探针先确认 `active=false`；Activity 真正重新进入前台后探针确认 `active=true`，发布计数前进。该回归只证明现有进程能在自然刷新点补回状态卡，不证明锁屏、长待机、重启或厂商清理后的持续可见性。

`specialUse` FGS A/B 仍未运行；在普通监听拓扑出现可归因的实际漏记前，不因“更像常驻”而启用第二个长期组件。

目标设备当前活动状态卡的应用 API 级检查同时确认：`FLAG_ONGOING_EVENT` 与 `FLAG_ONLY_ALERT_ONCE` 均存在，主体可见性为 `PRIVATE`，公开版本存在且可见性为 `PUBLIC`；用数据库最新条目的标题和正文在进程内比较，公开版本均未包含二者。检查只输出布尔值，不导出任何通知文字。该结果证明交给系统的通知对象满足脱敏模型，不替代红魔锁屏界面的肉眼渲染验收，也不证明其他系统界面不会按厂商规则显示额外内容。
