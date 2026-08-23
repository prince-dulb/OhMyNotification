# P0-203 / G3 Room schema v1 决策

状态：`[已确认] G3_APPROVED / SCHEMA_V1`

批准时间：2026-08-23（Asia/Shanghai）

用户明确批准创建首次 Room schema v1。该批准只覆盖本文件中的首次建库，不包含未来 schema 版本、表列索引变化或数据迁移。

## 1. 表与职责

### `notification_items`

长期通知条目主表。保存来源、Android 系统身份与生命周期代次、接收/更新时间、规范化标题正文、来源标签、内容指纹、动作能力摘要和移除事实。原始 `PendingIntent`、`Intent`、extras、图标、大图和媒体不入表。

唯一约束：`sourcePackage + sourceUserRef + systemKey + lifecycleGeneration`。同一活动代次的发布、更新和重连补扫更新原行；已移除后同身份再次发布创建下一代。

索引：

- `sortTimeEpochMillis DESC, itemId DESC`：全局稳定时间轴；
- `sourcePackage, sortTimeEpochMillis DESC, itemId DESC`：多应用筛选；
- `sourcePackage, sourceUserRef, systemKey, lifecycleGeneration`：身份查找与唯一约束。

### `health_evidence`

保存最小健康事实：进程开始、监听连接/断开、通知事务提交和状态通知权限/发布事实。只保存枚举、UTC 时间、运行会话与连接 ID，不保存通知正文。按后续保留策略有界；v1 先保留用于真实使用观察，不自动删除长期通知条目。

索引：`occurredAtEpochMillis DESC, evidenceId DESC`。

### `excluded_sources`

保存用户明确选择“不监控”的来源包名和修改时间。OMN 自身包名由代码固定排除，不写此表。排除只影响未来采集，不删除既有条目。

## 2. 事务规则

一次通知观察在单事务内完成：查找最新生命周期、插入或更新 `notification_items`，并在需要时写入不含正文的正面健康事实。新逻辑条目与移除事实始终写健康证据；同一活动生命周期的正文更新只在当前连接距离上一条正面证据至少 5 分钟时写 `NOTIFICATION_COMMITTED`，期间的新条目、移除、连接或恢复证据会重置该窗口；完全无变化的补扫不写。该限制只减少用于派生蓝黄区间的重复事实，不延迟或丢弃通知条目正文更新，也不增加定时器或周期唤醒。事务成功后才注册当前进程 `PendingIntent`、更新常驻状态摘要或向 UI 宣告记录成功。

精确重放和相同 `RECOVERY_SNAPSHOT` 不新增条目；内容指纹相同仍可更新时间/动作能力，但不增加逻辑条目。同一 key 已移除后再次发布才创建新代次。

## 3. 备份、迁移与动作边界

- `allowBackup=false` 且数据提取规则排除数据库；通知正文不进入系统云备份或设备迁移。
- schema 版本固定为 1，不提供迁移；首次建库使用 Room 生成 schema。
- 禁止 `fallbackToDestructiveMigration()`；未来版本必须先取得授权并提供迁移测试。
- 原始 `PendingIntent` 只保存在当前进程有界仓储；进程重建后既有条目显示运行时精确跳转不可用或来源应用降级，不反序列化动作。

## 4. 锁定依赖

依据 2026-08-23 官方公开 Maven 元数据：Room `2.8.4`、Paging `3.5.1`、KSP Gradle plugin `2.3.11`。依赖均为纯 JVM/AndroidX 路径，不引入 32 位原生库。
