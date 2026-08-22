# P0-001 Android 工具链基线

状态：`[已验证] GO`

盘点时间：2026-08-22（Asia/Shanghai）

## 1. 目标与边界

本次只读盘点现有 Android Studio、SDK、JDK、Gradle 与依赖缓存，回答“能否在不安装全局依赖、不修改系统配置的前提下创建 Phase 0 验证工程”。

本机绝对路径只记录在被 Git 忽略的 `.local-evidence/` 中；本文只保留可公开的版本和能力事实。

## 2. 已观察事实

| 项目 | 已有能力 | 结论 |
|---|---|---|
| Android Studio | `2025.3.4`，构建 `AI-253.32098.37.2534.15336583`，x86_64 | 可用；官方兼容范围最高到 AGP 9.2 |
| Studio JBR | JetBrains OpenJDK `21.0.10`，x86_64 | 可作为项目构建 JVM，不修改系统 `JAVA_HOME` |
| 其他 JDK | Oracle JDK `21.0.8`；PATH 上有 Microsoft OpenJDK `17.0.19` | 均不是当前阻断；优先使用 Studio JBR 保持 IDE/CLI 一致 |
| Android SDK Platform | API 35 与 Android 16 QPR2 API `36.1` | 满足 Android 16-only 工程的编译需求 |
| SDK Build Tools | `35.0.0`、`36.1.0`、`37.0.0` | 采用已安装的 `36.1.0`，不下载预览工具 |
| Platform Tools / ADB | `37.0.0` / ADB `1.0.41` | 可执行；设备连接留到真机任务验证 |
| Emulator | `36.5.11`，没有系统镜像 | 不阻断；本项目以红魔真机为首要证据，不为 Phase 0 强装镜像 |
| NDK | 未安装 | 不阻断；v0 保持纯 Kotlin/Java/AndroidX，不引入原生库 |
| SDK Command-line Tools | 未安装 | 当前已有目标 SDK 与构建工具，P0-003 不依赖 `sdkmanager`；需要变更 SDK 时进入 G1 |
| 已缓存 Gradle | `8.12` 可由 Studio JBR 21 正常启动 | 仅用于生成项目 Wrapper，不作为最终构建版本 |
| 已缓存 Android 依赖 | AGP `8.9.1`、KGP/Compose Compiler `2.1.0`、Compose BOM `2025.05.00` 等 | 版本过旧，不拿缓存偶然状态冒充当前基线 |

环境变量 `ANDROID_HOME`、`ANDROID_SDK_ROOT`、`JAVA_HOME` 和 `GRADLE_HOME` 均未设置；Android 工具也未加入系统 PATH。Phase 0 使用 `local.properties`、项目 Wrapper 和单次进程环境变量，不修改用户或系统配置。

## 3. 锁定的 P0-003 构建组合

| 项目 | 固定值 | 依据 |
|---|---|---|
| Android Gradle Plugin | `9.2.1` | Android Studio 2025.3.4 官方支持 AGP 7.1—9.2；9.2.1 是 9.2 补丁版 |
| Gradle Wrapper | `9.4.1-bin` | AGP 9.2 的官方最低与默认版本均为 9.4.1 |
| 构建 JVM | Studio JBR `21.0.10` | Gradle 9.4.1 支持在 Java 21 上运行；不需要新增 JDK |
| Kotlin | AGP 9.2 内置 Kotlin，KGP 运行时基线 `2.3.10` | AGP 9 默认启用内置 Kotlin，不应用 `org.jetbrains.kotlin.android` |
| Compose Compiler 插件 | `2.3.10` | 与 AGP 9.2 内置 Kotlin 版本对齐，不使用独立旧编译器坐标 |
| Compose BOM | `2026.06.01` | 盘点日最后一个仍停留在 Compose 1.11.x、可与 API 36.1 组合的稳定 BOM；不采用要求 API 37 的 `2026.08.00` |
| Activity Compose | `1.13.0` | 同一官方设置页给出的集成版本 |
| `compileSdk` | Android 16 QPR2 `36.1` | 使用官方 `release(36) { minorApiLevel = 1 }` DSL |
| `targetSdk` / `minSdk` | `36` / `36` | 项目已确认只支持 Android 16 及以上；不增加旧系统兼容分支 |
| SDK Build Tools | `36.1.0` | 本机已有，属于官方 Android 16 QPR2 要求的最新 36.x 工具 |

所有版本使用精确值，不使用 `+`、`latest` 或预览版。下载只由项目 Wrapper 和声明式项目依赖触发，不进行全局安装。

## 4. 官方依据

- [Android Gradle Plugin 与 Android Studio/Gradle 兼容表](https://developer.android.com/build/releases/about-agp)
- [AGP 9.2.0/9.2.1 发布说明与 API/JDK/Build Tools 边界](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [AGP 9 内置 Kotlin 迁移说明](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Compose Compiler 与依赖设置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
- [Compose BOM 使用与版本映射](https://developer.android.com/develop/ui/compose/bom)
- [Compose 1.12/API 37 边界说明](https://developer.android.com/blog/posts/what-s-new-in-the-jetpack-compose-august-26-release)
- [Android 16 QPR2 SDK 与 `compileSdk` DSL](https://developer.android.com/about/versions/16/qpr2/setup-sdk)
- [Gradle 9.4.1 发布说明](https://docs.gradle.org/9.4.1/release-notes.html)
- [Gradle Java 运行时兼容矩阵](https://docs.gradle.org/current/userguide/compatibility.html)

官方资料核对日期：2026-08-22。后续升级工具链时重新核对，不把本文版本当作永久最新值。

## 5. 风险与后续门

- 第一次 Wrapper/依赖解析需要联网下载项目文件；若网络或仓库不可达，记录原始错误，不改用未知镜像或关闭校验。
- 当前 ADB 版本读取成功；沙箱内首次设备枚举因临时日志目录不可写而未完成。这是执行环境限制，不等于 ADB 或手机失败，真机任务将使用可写临时目录重新验证。
- 若构建证明 Android Studio 2025.3.4、AGP 9.2.1、Compose Compiler 2.3.10 或 API 36.1 组合不兼容，P0-003 标记 `BLOCKED/REDIRECT`，先更新本文和规则，不添加绕过标记。
- 缺少 SDK 组件、需要 IDE/JDK 升级或需要修改系统配置时进入 G1，未经用户批准不执行。

## 6. 决策

`P0-001 = GO`：现有工具链足以在零全局安装、零系统配置变更的前提下创建并验证原生 Android 工程。下一步按 P0-002 已确认规则执行 P0-003。
