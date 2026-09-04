# 救救我的通知！ / Oh My Notification!

一个只在本机工作的 Android 通知记录器，目标是可靠保存通知历史，并尽可能保留返回原内容的能力。

项目当前正式版本是 **[v1.0.0](https://github.com/prince-dulb/OhMyNotification/releases/tag/v1.0.0)**。用户已完成一段时间的红魔真机日用，并于 2026-09-05 确认现有功能和交互足以作为 v1 发布。Android 应用已经启用通知监听、本地 Room 归档、更新归并、稳定时间轴、可调时间窗应用抽屉、蓝黄健康轨、常驻状态通知、来源筛选、通知类型与监控排除设置，以及运行时原内容动作。应用抽屉标题行集中提供“不要再记录、仅查看、不查看”三个来源级动作，通知内容区不重复 App 管理入口。默认收件箱只显示当前仍可尝试原通知动作的记录，其余历史进入设置页可访问的“当前无跳转动作归档”；点按归档条目会有限重扫一次系统当前活动通知，只有重新关联到同一条记录时才尝试打开。每条记录另有显式、需确认的永久删除动作，删除不改变来源监控或蓝黄健康轨。

`v1.0.0` 的产品范围已经锁定，正式签名 APK 已通过本地校验和红魔 Android 16 无损覆盖升级：版本从 `0.1.9-mvp` 升到 `1.0.0`，首次安装时间、通知权限、监听授权和 530 条本地记录均保留。B 站通知类型逐项矩阵、长待机量化和万级历史性能属于后续增强证据，不阻挡 v1，但不得写成已经验证。

当前候选的核心边界：原通知被划掉后，只要 OMN 当前取得的运行时动作仍可派发，归档条目可以回到原内容。`PendingIntent` 没有受支持的磁盘持久化方式，但进程重建后系统补扫、重新投递或通知生命周期变化可能重新提供动作；实际恢复边界仍以真机观察为准，不能把“进程重建”直接写成必然失效。

## 当前技术基线

- 原生 Kotlin、Jetpack Compose、Material 3
- Android 16 及以上，`applicationId` 为 `io.github.prince_dulb.ohmynotification`
- Gradle Wrapper 9.4.1、Android Gradle Plugin 9.2.1
- GPL-3.0-or-later

## 本地验证

```powershell
.\gradlew.bat --version
.\gradlew.bat test
.\tools\verify-testdata.ps1
.\gradlew.bat lint
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:assembleDebugAndroidTest
.\tools\verify-apk-boundary.ps1
.\tools\verify-installable-apk.ps1
# 手机通过 ADB 授权后：
.\tools\install-and-smoke-test.ps1
```

正式 APK 从 [GitHub Release](https://github.com/prince-dulb/OhMyNotification/releases/tag/v1.0.0) 下载；正式签名证书和 APK 摘要见[发布说明](docs/发布说明-v1.0.0.md)。APK 不进入 Git，只作为 Release 资产发布。已安装 `0.1.x-mvp` Debug 版的目标红魔必须覆盖升级，不要先卸载，以保留本地归档与授权。

详细需求、架构边界和阶段门见 [`docs/开发计划书/README.md`](docs/开发计划书/README.md)。
