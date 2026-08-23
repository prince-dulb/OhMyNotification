# P0-005 本地证据与敏感扫描

状态：`[候选] IMPLEMENTED / NOT_VERIFIED`

## 1. 目的

Phase 0 真机实验会接触真实通知正文、动作引用、内容 ID、设备标识和本机路径。本任务建立可关联 commit、构建、设备和数据集的私有证据格式，并在任何 Git 提交前扫描公开候选树；它不把自动扫描描述成绝对防泄漏保证。

## 2. 私有证据入口

```powershell
.\tools\new-phase0-evidence.ps1 -SpikeId SP-01 -ArtifactPath .\artifacts\OhMyNotification-0.0.0-phase0-debug.apk
```

脚本只在被 Git 忽略的 `.local-evidence/phase-0/` 下创建：

- `<run-id>.json`：符合 [P0-005-evidence-schema.json](P0-005-evidence-schema.json) 的运行模板；
- `<run-id>.sentinels.txt`：每行一个本次实验出现的精确私有值，供公开树泄漏扫描使用。

设备序列号只保存 SHA-256。公开输出只显示证据文件名、commit 短摘要和工作树是否有改动，不输出 SDK 路径、序列号或哨兵内容。

## 3. 公开树扫描入口

```powershell
.\tools\verify-sensitive-content.ps1 -SelfTest
```

扫描范围是 Git 已跟踪文件与未忽略的新文件，包括：

- 被禁止跟踪的私有证据、APK、签名材料、数据库、本机配置和环境文件；
- 用户目录、绝对 SDK 配置、私钥、常见 Token/API Key、Bearer 凭据和原始设备序列号字段；
- `.local-evidence/phase-0/*.sentinels.txt` 中登记的精确私有值。

命中时只输出规则 ID 和公开候选文件路径，不重复打印敏感原文。自动规则无法识别所有自然语言通知内容，因此真实实验仍必须把正文、目标 ID、动作令牌、原始序列号和私有路径主动登记为哨兵，并进行人工公开摘要复核。

## 4. 完成门

P0-005 只有在以下条件同时满足后才能改为 `GO`：

1. PowerShell 语法检查通过；
2. 扫描器内存自测覆盖每条内容规则；
3. 公开候选树扫描通过；
4. 在已授权红魔上成功生成一份带 commit、设备摘要和 APK 摘要的私有 `SP-01` 模板；
5. 验证 JSON 不包含原始设备序列号或本机绝对路径；
6. Git 状态证明 `.local-evidence/` 仍被忽略。
