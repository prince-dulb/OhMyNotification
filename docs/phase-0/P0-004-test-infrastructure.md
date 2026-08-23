# P0-004 测试基础设施基线

状态：`[已验证] GO（红魔 11 Pro+ / Android 16 设备合同通过）`

验收时间：2026-08-23（Asia/Shanghai）

## 1. 范围

本任务建立 T0—T4 可继续扩展的最小测试链路：固定去隐私数据、JVM 契约、独立 instrumentation 测试 APK、受控通知源、设备命令和 APK 边界检查。它不实现产品通知监听、Room schema 或真实 B 站验收，也不把编译成功写成设备运行成功。

## 2. 已落地内容

| 层级 | 当前证据 | 状态边界 |
|---|---|---|
| T0 | 固定数据校验、PowerShell 语法检查、production/test APK 内容与权限扫描 | 已通过 |
| T1 | `notification-fields-v1` 的 SHA-256、隐私哨兵和固定种子派生 ID 契约，共 2 项 JVM 测试 | 2/2 通过 |
| T2 | 尚无 Room schema 或持久化测试 | `NOT_APPLICABLE`；等待 G3 |
| T3 | AndroidX Runner 测试 APK 可编译，包含发布—更新—动作落地—移除契约及受控内部落页 | 红魔 11 Pro+ / Android 16 通过 |
| T4 | `invoke-controlled-notification.ps1` 可选择合成 case 并执行发布、更新、动作落地、移除或完整契约 | 完整合同已在红魔通过 |

测试 APK 使用 `io.github.prince_dulb.ohmynotification.test`，不增加第二个产品模块。固定测试依赖为 JUnit `4.13.2`、AndroidX Test Runner `1.7.0` 和 Ext JUnit `1.3.0`；AndroidX 版本依据为验收日核对的[官方稳定版清单](https://developer.android.com/jetpack/androidx/releases/test)。

## 3. 合成数据

首个数据集为 `notification-fields-v1`，固定种子 `2026082301`，包含 6 个合成 case：缺标题、缺正文、Unicode、4097 字符长文本、非法时间和受控动作。

`dataset.json` 的固定 SHA-256：

```text
65776ceee66733c40c476c080136973a5546350781a0d8da713e3289ef059061
```

数据不含真实包名、账号、内容 ID、URI、通知正文或本机路径。语义变化必须新建数据集版本，不得覆盖此基线。

## 4. 可控通知源与隔离

- instrumentation 测试 APK 从合成数据发布、更新和移除一个稳定身份的通知。
- `action` case 使用不可导出的纯 Java 测试 Activity 构造 `PendingIntent`；合同会实际调用 `send()`，并以目标页私有回执区分“动作引用存在”和“动作确实落地”。
- 命令测试只有收到 instrumentation 参数时才执行；普通设备测试不会留下通知。
- 测试 APK 实测只有 `POST_NOTIFICATIONS` 与 AndroidX Runner 所需的 `REORDER_TASKS`，没有网络或账号权限。
- production APK 不含 `OMN_TEST_ONLY_NOTIFICATION`、`ControlledNotificationSource`、`notification-fields-v1` 或测试资产。

## 5. 验证结果

### 无设备总门

```powershell
.\gradlew.bat test lint :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

结果：`BUILD SUCCESSFUL`，126 个 task；JVM 测试 2 项全部通过，Lint 无错误，Debug、未签名 Release、Android 测试 APK 和设备测试均成功完成。

### 固定数据与 APK 边界

```powershell
.\tools\verify-testdata.ps1
.\tools\verify-apk-boundary.ps1
.\tools\verify-installable-apk.ps1
```

三项均通过。Debug APK 通过 Android Debug 签名、4/16 KiB 对齐、包名、版本、Launcher、Android 16 `minSdk/targetSdk` 和 `arm64-v8a` 检查；本次构建 SHA-256 为：

```text
2830d999e65fc18920e13c218daac0c65feed079e3d6fcef874dc63adb907fdc
```

## 6. 路径故障与修复

非 ASCII 工作区下，Kotlin 测试类能够编译，相同类和依赖由 JUnit 直接运行也能通过，但 Gradle Test Executor 会破坏测试输出路径并报 `ClassNotFoundException`。更换 JDK 和进程编码均未解决。

用户将正式工作区改为 ASCII 名称后，未改测试代码的标准 `gradlew test` 立即恢复通过。项目据此删除 `android.overridePathCheck`，把 ASCII 工作区写入工程规则；目录联接和关闭测试均不是接受的解决方案。

## 7. Android 16 设备缺陷与修复

首次设备运行暴露了三处只靠编译无法发现的问题：

1. instrumentation 测试代码虽然取得测试 APK 的 `Context`，仍由产品进程 UID 调用通知服务，Android 16 因调用 UID 与通知包名不一致抛出 `SecurityException`；修复为由测试 APK 自身的导出命令 Activity 执行。
2. 独立启动的测试 APK 组件不能依赖 instrumentation 目标应用提供 Kotlin 运行库；命令入口和落页改为纯 Java，测试驱动仍留在 Kotlin instrumentation 中。
3. 固定数据的历史 `postedAtEpochMillis` 被通知服务统计为 `numTooOld`，表现为已入队但从未发布；测试通知改用当前展示时间，并把固定夹具时间保存在测试专用 extra 中。

动作目标页使用测试 APK 私有子进程，避免命令入口等待落地回执时阻塞同一主线程；目标页写入回执后立即结束，不残留测试界面。

设备命令：

```powershell
.\tools\invoke-controlled-notification.ps1 -Operation contract -Install
.\gradlew.bat connectedDebugAndroidTest
```

结果：完整合同 `OK (1 test)`，发布、同身份更新、真实动作落地和移除全部通过；完整设备测试 `BUILD SUCCESSFUL`，无失败，未携带命令参数的命令测试按设计跳过。

## 8. 决策与未验证项

`P0-004 = GO`：桌面侧测试基础设施、数据基线、测试 APK 隔离和构建命令成立，可以进入 P0-005。

P0-004 的桌面门、APK 隔离和红魔设备合同均已通过。以下内容仍不属于本任务的通过范围：

- P0-101 debug 监听器是否收到受控源和真实 B 站回调；
- 红魔长待机与重启后的后台行为；
- B 站真实通知、长期动作寿命和性能。

这些未运行项必须在相应设备任务中取得真实证据，不能由本任务的绿色构建结果替代。
