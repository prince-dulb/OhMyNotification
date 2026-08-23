# Phase 0 APK 安装启动冒烟协议

状态：`[待验证] READY / NOT_RUN`

## 1. 目的

本协议只证明本地 Debug APK 能在目标 Android 16 设备上安装并启动，不证明通知监听、持久化、跳转寿命、后台可靠性或性能。

## 2. 固定入口

```powershell
.\tools\install-and-smoke-test.ps1
```

存在多个授权设备时必须使用 `-Serial` 明确目标。脚本不卸载应用、不修改系统设置、不读取通知内容，也不把设备序列号写入公开输出。

## 3. PASS 条件

一次 run 必须同时满足：

1. 交付 APK 的签名、4/16 KiB 对齐、包名、版本、Launcher、SDK 与 arm64 静态检查通过；
2. 设备 SDK 不低于 36，ABI 列表包含 `arm64-v8a`；
3. `adb install -r` 明确返回 `Success`；
4. Activity Manager 启动 `MainActivity` 并返回 `Status: ok`；
5. 启动后能观察到应用进程；
6. `MainActivity` 能观察为 resumed activity。

任一条件缺失即为 `FAIL`，不能用“命令没有明显报错”替代。

## 4. 证据边界

每次实际 run 写入被 Git 忽略的 `.local-evidence/phase-0/P0-APK-smoke-<timestamp>.json`，包含：

- 带时区时间；
- APK 文件名与 SHA-256；
- 设备序列号的 SHA-256，不保存明文序列号；
- 私有设备 SDK、ABI 和型号；
- 安装、启动、进程和 resumed activity 四项布尔结果；
- 失败时的去敏错误摘要。

公开报告只写设备类别、Android 版本、APK 摘要和 PASS/FAIL，不复制私有设备标识或完整 `adb` 输出。

## 5. 当前结果

验收脚本建立时授权设备数为 0，因此状态保持 `NOT_RUN`。只有设备实际连接并完成上述六项检查后，本文才能改为 `[已验证] PASS`。
