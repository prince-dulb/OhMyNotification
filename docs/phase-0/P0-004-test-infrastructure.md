# P0-004 测试基础设施基线

状态：`[已验证] GO（设备运行 NOT_RUN）`

验收时间：2026-08-23（Asia/Shanghai）

## 1. 范围

本任务建立 T0—T4 可继续扩展的最小测试链路：固定去隐私数据、JVM 契约、独立 instrumentation 测试 APK、受控通知源、设备命令和 APK 边界检查。它不实现产品通知监听、Room schema 或真实 B 站验收，也不把编译成功写成设备运行成功。

## 2. 已落地内容

| 层级 | 当前证据 | 状态边界 |
|---|---|---|
| T0 | 固定数据校验、PowerShell 语法检查、production/test APK 内容与权限扫描 | 已通过 |
| T1 | `notification-fields-v1` 的 SHA-256、隐私哨兵和固定种子派生 ID 契约，共 2 项 JVM 测试 | 2/2 通过 |
| T2 | 尚无 Room schema 或持久化测试 | `NOT_APPLICABLE`；等待 G3 |
| T3 | AndroidX Runner 测试 APK 可编译，包含发布—更新—移除契约及受控内部落页 | 构建通过；设备运行 `NOT_RUN` |
| T4 | `invoke-controlled-notification.ps1` 可选择合成 case 并执行发布、更新、移除或完整契约 | 命令已建立；红魔运行 `NOT_RUN` |

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
- `action` case 使用不可导出的测试 Activity 构造 `PendingIntent`，用于区分“通知存在”和“受控动作能实际落页”。
- 命令测试只有收到 instrumentation 参数时才执行；普通设备测试不会留下通知。
- 测试 APK 实测只有 `POST_NOTIFICATIONS` 与 AndroidX Runner 所需的 `REORDER_TASKS`，没有网络或账号权限。
- production APK 不含 `OMN_TEST_ONLY_NOTIFICATION`、`ControlledNotificationSource`、`notification-fields-v1` 或测试资产。

## 5. 验证结果

### 无设备总门

```powershell
.\gradlew.bat test lint :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

结果：`BUILD SUCCESSFUL`，124 个 task；JVM 测试 2 项全部通过，Lint 为 `0 errors, 7 warnings`，Debug、未签名 Release 和 Android 测试 APK 均成功生成。

### 固定数据与 APK 边界

```powershell
.\tools\verify-testdata.ps1
.\tools\verify-apk-boundary.ps1
.\tools\verify-installable-apk.ps1
```

三项均通过。Debug APK 通过 Android Debug 签名、4/16 KiB 对齐、包名、版本、Launcher、Android 16 `minSdk/targetSdk` 和 `arm64-v8a` 检查；本次构建 SHA-256 为：

```text
3f20e8a09f94f6517a0312efaaaa29cbb044090da8ac30d748f5ce250143c60f
```

## 6. 路径故障与修复

非 ASCII 工作区下，Kotlin 测试类能够编译，相同类和依赖由 JUnit 直接运行也能通过，但 Gradle Test Executor 会破坏测试输出路径并报 `ClassNotFoundException`。更换 JDK 和进程编码均未解决。

用户将正式工作区改为 ASCII 名称后，未改测试代码的标准 `gradlew test` 立即恢复通过。项目据此删除 `android.overridePathCheck`，把 ASCII 工作区写入工程规则；目录联接和关闭测试均不是接受的解决方案。

## 7. 决策与未验证项

`P0-004 = GO`：桌面侧测试基础设施、数据基线、测试 APK 隔离和构建命令成立，可以进入 P0-005。

验收时没有授权 Android 设备连接，因此以下内容保持 `NOT_RUN`：

- `connectedDebugAndroidTest`；
- 受控通知在 Android 16 上的实际发布、更新、移除与落页；
- 红魔后台行为、B 站真实通知、长期动作寿命和性能。

这些未运行项必须在相应设备任务中取得真实证据，不能由本任务的绿色构建结果替代。
