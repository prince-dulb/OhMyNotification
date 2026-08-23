# 救救我的通知！ / Oh My Notification!

一个只在本机工作的 Android 通知记录器，目标是可靠保存通知历史，并尽可能保留返回原内容的能力。

项目当前处于 **Phase 0 技术验证**。仓库里的 Android 应用只是可安装验证壳；通知监听、常驻状态、本地持久化与时间轴尚未启用。

## 当前技术基线

- 原生 Kotlin、Jetpack Compose、Material 3
- Android 16 及以上，`applicationId` 为 `io.github.prince_dulb.ohmynotification`
- Gradle Wrapper 9.4.1、Android Gradle Plugin 9.2.1
- GPL-3.0-or-later

## 本地验证

```powershell
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

详细需求、架构边界和阶段门见 [`docs/开发计划书/README.md`](docs/开发计划书/README.md)。
