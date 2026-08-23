# 救救我的通知！ / Oh My Notification!

一个只在本机工作的 Android 通知记录器，目标是可靠保存通知历史，并尽可能保留返回原内容的能力。

项目当前已进入 **首个可日用 MVP**。Android 应用已经启用通知监听、本地 Room 归档、更新归并、稳定时间轴、时间窗应用抽屉、蓝黄健康轨、常驻状态通知、来源筛选、监控排除和运行时精确跳转。首版按“先解决最基础、最核心问题，问题边用边修”的口径侧载；B 站三类逐项验收、重启与长待机矩阵、工作资料和完整性能预算继续在后续迭代补齐。

当前候选的核心边界：原通知被划掉后，只要 OMN 进程中的运行时动作仍在，归档条目可以精确回到原内容；进程重建后，已经划除通知的 `PendingIntent` 没有受支持的磁盘恢复方式，界面会诚实显示精确跳转不可用，并只在用户确认后提供“打开来源应用”降级。

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

`artifacts/OhMyNotification-0.1.3-mvp-debug.apk` 是本地生成、带 Android Debug 签名的当前侧载 MVP；它不会进入 Git，也不是公开 release。目标红魔设备使用覆盖安装保留本地归档与授权。

详细需求、架构边界和阶段门见 [`docs/开发计划书/README.md`](docs/开发计划书/README.md)。
