# P0-003 原生 Android 工程基线

状态：`[已验证] GO`

验收时间：2026-08-23（Asia/Shanghai）

## 1. 范围

本任务只建立可复现、可安装的原生 Android 验证壳，并证明默认构建命令可工作。它不实现通知监听、常驻状态通知、Room schema 或产品时间轴，也不把空壳表述为可用版本。

## 2. 已落地工程

| 项目 | 结果 |
|---|---|
| 模块 | 单一 `:app`，没有平行临时应用 |
| 技术栈 | 原生 Kotlin、Jetpack Compose、Material 3；无 Flutter、无 NDK/C++ 构建 |
| 应用身份 | `io.github.prince_dulb.ohmynotification`，版本 `1 / 0.0.0-phase0` |
| Android 边界 | `minSdk 36`、`targetSdk 36`、Android 16 QPR2 `compileSdk 36.1` |
| 工具链 | Gradle Wrapper `9.4.1`、AGP `9.2.1`、AGP 内置 Kotlin与 Compose Compiler `2.3.10` |
| 界面壳 | Material 3、系统动态配色、深浅色、edge-to-edge、安全绘制 inset、中英文资源 |
| 图标 | 通用矢量回退 + 自适应图标 + Android 单色主题图标；当前为 Phase 0 占位资产，不是最终视觉身份 |
| 数据边界 | 显式禁用云备份与设备迁移的数据提取；不允许明文网络流量 |
| 权限边界 | 未声明联网、通知访问、前台服务或其他用户授权权限；APK 仅含 AndroidX 自动合并的应用内签名级动态接收器权限 |

## 3. 路径验证事实

P0-003 最初在含非 ASCII 字符的正式工作区完成 Kotlin、资源、Lint、Debug 和 Release 全链路；当时 `test` 尚无测试源，因此该结果只能证明构建链路，不能证明 Gradle 测试工作进程能够处理同一路径。

P0-004 加入真实 JVM 测试后，Gradle Test Executor 会破坏非 ASCII 测试类路径并报 `ClassNotFoundException`；相同类文件和依赖由 JUnit 直接运行可以通过。用户随后将工作区改为 ASCII 名称，标准 `gradlew test` 恢复为 2 项测试全部通过。

因此早先“编译链能够处理该路径”的事实仍成立，但不能外推为“完整工具链支持该路径”。项目已移除 `android.overridePathCheck` 并把 ASCII 工作区写成固定规则；P0-003 的工程骨架结论不受影响。

## 4. 验证结果

### Wrapper

```powershell
.\gradlew.bat --version
```

结果：Gradle `9.4.1`，Launcher/Daemon JVM 为 JetBrains Runtime `21.0.10`。

### 完整门

```powershell
.\gradlew.bat test lint :app:assembleDebug :app:assembleRelease
```

最终结果：`BUILD SUCCESSFUL`，共 92 个 task；Configuration Cache 正常复用。

- `test` 成功但当前为 `NO-SOURCE`；P0-004 才负责建立 T0—T4 测试链路，本文不宣称已有测试覆盖。
- Lint 为 `0 errors, 7 warnings`。
- 其中 6 条是已知的版本锁定提示：当前 Android Studio/SDK 组合不能直接采用 Lint 建议的 AGP 9.3、API 37 与 Compose 1.12 组合。
- 另 1 条是 `mipmap-anydpi-v26` 的 `ObsoleteSdkInt`：按提示合并为无版本 adaptive-icon 后，AAPT 实际报 `resource mipmap/ic_launcher not found`；恢复标准的通用图标 + `v26` 自适应覆盖后构建成功。该警告保留为可见证据，没有全局关闭。
- Debug APK 与本地未签名 Release APK 均生成成功；没有复制到 `artifacts/`，也没有发布。

## 5. APK 清单与 ABI 核对

构建后用 SDK Build Tools 检查 APK：

- 包名、版本、中文/英文标签、`minSdk`、`targetSdk` 和 `compileSdk` 与表中一致。
- Release APK 没有 `debuggable` 标记。
- 没有 `INTERNET`、通知监听或前台服务声明。
- Compose/AndroidX 传递打包 `libandroidx.graphics.path.so`，包含 `arm64-v8a`、`armeabi-v7a`、`x86` 和 `x86_64`；四份未压缩体积合计约 37 KiB。目标红魔具备所需的 `arm64-v8a`，不存在“仅 32 位依赖”问题。
- 项目本身没有本地源码、NDK 插件或 NDK 安装依赖；后续仍须按每次依赖变更复核 APK，而不是只看 Gradle 声明。

## 6. 决策

`P0-003 = GO`：默认项目命令能够在正式工作区构建原生 Android 16 验证壳，包名与技术栈边界成立。下一项是 P0-004：建立有真实断言的测试链路、固定合成数据与不会进入生产 APK 的可控通知源。
