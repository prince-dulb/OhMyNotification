# P0-108 / SP-08 排序、抽屉、筛选与分页

状态：`[已验证] LOGIC_GO / ROOM_RELEASE_PERF_NOT_RUN`

验收时间：2026-08-23（Asia/Shanghai）

## 1. 范围与结论边界

本 Spike 已锁定并验证 A06/A07/A12 的纯 Kotlin 逻辑契约：稳定基础全序、严格游标、来源筛选、`ANCHOR_V1` 抽屉规则、分页大小不变量和有界页面消费。

测试源使用进程内集合模拟已经建立索引的本地存储。它能证明查询消费者不必一次接收全部历史，不能证明未来 Room 查询计划、release PSS、首屏时间或红魔真机预算；后四项保持 `NOT_RUN`，由 P0-109 和 G3 候选 schema 继续验证。

## 2. 固定数据与参数

版本化数据集：`timeline-query-v1`

```text
seed=2026082308
sha256=59d4ac4c20f5173cbd962ad3746ee34fb3f11b1ae81091b1c2548f10302bef9c
```

锁定逻辑：

- 基础全序为 `firstReceivedAtMillis DESC, ingestSequence DESC, unsignedCanonicalItemId DESC`；
- 空来源集合表示查看全部，非空集合为 `AppUserKey` 的 OR 条件；
- 筛选先于排序结果分页和抽屉分组；
- `ANCHOR_V1` 使用闭区间 15 分钟、最多跨 3 条其他来源、至少 2 个成员；
- 已被先前抽屉消费的其他来源记录仍按原始流逐条计入跨越数量；
- 抽屉 ID 只由策略版本、查询指纹、来源键和完整有序成员 ID 决定。

## 3. 自动化覆盖

纯 JVM 参考实现与测试位于 `app/src/test/.../phase0/sp08/`，不进入 APK。覆盖：

1. 相同时间下的 sequence 与无符号规范 ID 破平局；
2. 固定种子 10,000 轮反对称性与传递性检查；
3. 10,000 条使用页大小 1、10、37、100、257 的严格游标拼接；
4. 全部、单来源和多来源筛选的相对顺序与规范查询指纹；
5. 连续同源、短跨度 `A→B→A`、超过 15 分钟、恰好 15 分钟、跨 3/4 条边界；
6. 相同包名不同 Android 用户不得混组；
7. 已消费其他来源仍计数、成员唯一和非重叠；
8. 1,000 条在页大小 10、37、100 下得到完全相同节点与成员序列；
9. 500 条单应用突发的头部范围、总数和 37 条成员分页。

命令：

```powershell
.\tools\verify-testdata.ps1
.\gradlew.bat testDebugUnitTest --tests "io.github.prince_dulb.ohmynotification.phase0.sp08.*"
```

结果：两个数据集校验通过；10 项 SP-08 测试全部通过，无失败。

## 4. 页大小与有界消费证据

10,000 条完整基础全序的固定 SHA-256 为：

```text
189ed16336e16041bd002364497ec80550f2f6987f92fcd90e8665b381f32d2e
```

上述五种页大小均逐页更新摘要并得到同一哈希；单次返回不超过请求 `loadSize`。参考游标通过三元组二分定位下一页起点，全部来源场景检查的行数不超过 `itemCount + queryCount`。

500 条同来源突发使用 37 条页面、最多缓存 2 页的存储视图。分组头得到 `count=500`，成员分 14 页读取，消费者峰值缓存不超过 74 条摘要。底层测试存储仍持有合成源集合，因此这项证据只支持“算法接口允许有界读取”，不支持 Android 进程总内存结论。

## 5. 决策

`SP-08 LOGIC_GO`：稳定顺序、筛选语义、游标边界和 `ANCHOR_V1` 分组规则具有确定结果，可作为后续候选查询与 UI 的逻辑权威。

仍需在 P0-109 / G3 后验证：

- Room 物理列对规范 ID 无符号字典序的等价实现；
- 来源谓词与三元游标的实际索引及 `EXPLAIN QUERY PLAN`；
- 数据失效后的 Paging generation 与页尾有限前瞻；
- release 红魔 11 Pro+ 上的 10,000 条首屏、翻页、筛选、分组、PSS 和写盘预算。

## 6. 生产候选接入进度

经批准的 Room schema v1 落地后，生产候选已使用 Room `PagingSource` 在查询层执行全部/包名集合筛选，配置为 `pageSize=40`、`prefetchDistance=12`、`maxSize=200`；Compose 不先加载全表再过滤。最后应用的包名集合现保存在独立的应用私有 `SharedPreferences` 中，受全量数据提取排除规则保护，不写监控排除策略、不触发后台任务。筛选弹窗使用草稿，只有用户点击“应用”并成功持久化后才切换查询；取消不改变当前集合，空集合仍表示查看全部。

目标设备上的定向 instrumentation 测试已完成“保存两个合成包名 → 新建仓储实例读取 → 恢复用户原值 → 再次新建实例核对”闭环，结果通过，最终 `includedSourceFilterCount=0`，没有留下测试筛选。当前生产查询仍只使用包名，没有实现 A12 完整的 `sourceUser + sourcePackage` 粒度；工作资料隔离、真实 UI 用户验收和 release 10,000 条预算仍保持未完成。
