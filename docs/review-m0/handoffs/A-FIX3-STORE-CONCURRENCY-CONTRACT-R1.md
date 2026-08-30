# A-FIX3 Store Concurrency Contract R1 交接

## 1. 结论与拓扑

- 结论：`PASS / HANDOFF_READY`。
- 失败集成候选与固定起点：`cb1ded9b9dfcf762e28a1c1064bd58f85e40b957`（`integration/review-m0-a-fixes-r1`）。
- 分支：`fix/review-m0-a-fix3-store-concurrency-r1`。
- worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-a-fix3-store-concurrency-r1`。
- 实现结果 SHA：`4dea8e63150a0bcc7a8849fd6585fec5f86f9d7a`。
- 本文档提交的最终 SHA 无法在自身内容中稳定自指，由最终交付消息给出。
- 未修改生产 Java、主 checkout、其他 worktree 或 `milestone/review-m0`；未生成 diff/patch 文件；未运行完整 A、完整集成回归、B/C/D、P08/P09/UI；未调用远程模型或远程 MCP。

## 2. 合同审计与生产调用图

生产源码中 `ReviewM0IdempotencyStore.claim` 只有三处调用：

1. `ReviewM0ApiIdempotency.execute`：`claim -> 业务写 -> completeWithSnapshot` 全部位于一个 `REQUIRES_NEW` `TransactionTemplate` 中；外层 `executeWithRetry` 每次瞬态竞争重试都会重新进入完整新事务。
2. `ReviewM0ApiIdempotency.executeDynamic`：事务所有权与上一项相同，只是资源状态码动态计算。
3. `ReviewM0JobSubmissionService.submit`：自身 `TransactionTemplate` 为默认 `REQUIRED`，但全部生产调用都在 `ReviewM0ApiIdempotency.executeDelegatedCompletion` 的 `REQUIRES_NEW` 完整事务内：
   - checklist extraction：`ReviewM0ApiService.submitExtraction -> executeDelegatedCompletion(REQUIRES_NEW) -> ReviewM0JobSubmissionService.submit(REQUIRED)`；
   - formal review：`ReviewM0ApiService.submitReview -> executeDelegatedCompletion(REQUIRES_NEW) -> FormalReviewSubmissionService.submitOwned(REQUIRED) -> ReviewM0JobSubmissionService.submit(REQUIRED)`。

全生产树检索没有发现绕过 `ReviewM0ApiIdempotency` 完整事务重试所有者而直接依赖 store 承担并发的路径。`ReviewM0JobSubmissionService` 的 `REQUIRED` 事务会参与外层 `REQUIRES_NEW`，因此 claim、Job/业务写和首次不可变响应快照仍原子提交或整体回滚。

## 3. 根因复现与最终合同判断

固定候选上使用既有 `run-real-mysql8.ps1` 和真实 MySQL 8，稳定复现 `ReviewM0RealMysql8AcceptanceIT`：2 项发现、1 项通过、1 项 error。失败方法 `longScopesConcurrentUniquenessCollisionChecksAndV3ChecksAreReal` 让 8 个线程直接调用 `JdbcReviewM0IdempotencyStore.claim`；`SELECT ... FOR UPDATE` 发生 MySQL deadlock，`CannotAcquireLockException` 逃逸。

冻结的业务验收意图是：真实 MySQL 下长 scope、并发唯一性、完整字段碰撞 fail-closed、V003/check 约束；“并发线程直接调用低层 store”不是冻结合同，而是旧测试偶然绑定的实现细节。A-FIX1 已把瞬态竞争重试提升到完整 `REQUIRES_NEW` 业务事务外层，并有 8/16/48 路真实 HTTP 证据；恢复 store 内层短重试会在已经 rollback-only 的参与事务中继续执行，并可能重新引入 48 路中的 500。

最终判断是迁移旧验收入口到正式事务所有者，而不是修改生产事务边界：旧真实 MySQL 测试改为并发调用 `ReviewM0ApiIdempotency.execute`，每个尝试在同一事务中写真实 `review_m0_case` 业务行并完成快照。并发强度仍为 8，长 owner/path/key 长度、唯一性、完整碰撞、V003/check 断言均保留；没有 catch/忽略 deadlock、降低并发、skip 或整门自动重跑。

## 4. 改动

- `ReviewM0RealMysql8AcceptanceIT`：并发入口迁移到正式事务所有者；新增 8 个结果均为 201、1 次首写/7 次 replay、业务操作仅执行一次、业务行/COMPLETED claim/快照各一的真实 MySQL 断言；后续碰撞、V003 和长 material 唯一性断言不变。
- `ReviewM0ApiIdempotencySnapshotTest`：新增确定性回归。第一次在完成 claim、业务行和快照后注入 `CannotAcquireLockException`，事务同步状态必须严格为 `ROLLED_BACK -> COMMITTED`；第二次使用相同主键成功，最终 claim/业务行/快照各一，证明失败尝试没有残留且重试发生在全新完整事务。
- `run-real-mysql8.ps1`：同一 90 秒准备预算内，要求官方镜像日志出现 `MySQL init process done. Ready for start up.` 且 `SELECT 1` 成功。原 `mysqladmin ping` 会把镜像初始化临时 mysqld 误判为最终 ready，随后临时进程关闭造成连接空窗。脚本不重跑失败测试，也未修改 Hikari、等待预算或数据库不可用分类。
- 生产代码、异常/HTTP envelope、安全日志、配置和数据库迁移均未改。

## 5. 验收矩阵

| 验收项 | 结果 | 关键证据 |
| --- | --- | --- |
| 固定候选旧门根因 | FAIL（预期基线） | 真实 MySQL 8，2 discovered / 1 passed / 1 error；直接 store `SELECT ... FOR UPDATE` deadlock |
| 修复后既有 `run-real-mysql8.ps1` | PASS，连续 3 轮 | 每轮 2/2，Failures 0、Errors 0、Skipped 0；均为新的 MySQL 8 容器 |
| `run-real-mysql8-concurrent-idempotency.ps1` | PASS | seed 1/1 + MySQL 重启后 verify 1/1，均 0 failure/error/skip；覆盖双实例、8/16/48、回滚接管、异载荷 409、owner 隔离和重启重放 |
| 确定性完整新事务回归 | PASS | `ReviewM0ApiIdempotencySnapshotTest` 10/10；明确 `ROLLED_BACK -> COMMITTED`，最终业务/claim/snapshot 各一 |
| A-FIX1 直接相关组合 | PASS | 60/60（原 59 项 + 新增 1 项），Failures 0、Errors 0、Skipped 0 |
| Review 默认门 | PASS | 26/26，`ASSET_VERIFIED`，敏感信息扫描 1208 个文本文件无命中 |
| Review Integrated 直接门 | PASS | asset 26/26 + adapter 15/15 = 41/41，Failures 0、Errors 0、Skipped 0，`AUTO_VERIFIED` |
| 新测试源码编译 | PASS | `ai-service` test-compile `BUILD SUCCESS` |

A-FIX1 在本固定候选已有 fat Jar 双实例真实 HTTP 正常并发 8/16/48 全 201、500/503 均 0 的证据；本任务没有生产代码差异，新 concurrent runner 的同一正式事务路径 seed/verify 继续全过。因此没有重复构造完整 A 的 HTTP 门，也没有把未运行的远程或人工链路描述为新验证。

早期有宿主机 native-memory JVM 启动失败和 Docker Desktop 被停止的环境噪声；0 tests 的尝试没有计作通过。恢复环境后才执行并记录上述连续三轮旧门与完整矩阵。

## 6. 冻结合同保持

- `submissionFileName`、输入格式范围、owner 隔离和 API envelope 均未改。
- F4-R1 异常分类与 HTTP 行为、A-FIX1 的完整事务重试算法和等待策略均未改。
- 首次响应不可变快照仍与 claim/业务写在同一事务；新增测试反向证明失败尝试三类行全部回滚。
- 数据库完全不可用分类、Hikari 配置、等待预算、安全日志和 LOCAL_ONLY/P08/P09 状态均未改。
- 长 scope、并发唯一性、完整字段碰撞 fail-closed、V003/check 约束没有删除或弱化。

## 7. A-FIX4 冲突提示与剩余风险

- 与 A-FIX4 的已知直接文件冲突是 `ReviewM0ApiIdempotencySnapshotTest.java`；A-FIX4 还修改生产 `ReviewM0ApiIdempotency` 的构造/重试策略与数据库不可用分类。集成时不得机械覆盖：必须保留 A-FIX4 专属的 DB-unavailable 分类、等待预算和配置，同时移植本任务 `ROLLED_BACK -> COMMITTED` 及失败无业务/claim/snapshot 残留的断言。
- 本任务的 `ReviewM0RealMysql8AcceptanceIT` 与旧 MySQL runner 当时未见 A-FIX4 修改；若后续有脚本冲突，应保留最终 mysqld ready 判定和正式事务入口，不引入整门重跑。
- `ReviewM0IdempotencyStore` 仍是低层原子存储接口。未来新增生产调用者必须通过完整新事务重试所有者；直接并发调用 store 不获得自动重试保证。
- 未运行完整 A、完整集成回归、B/C/D、真实 MinIO/OCR/远程模型/MCP/浏览器；这些状态不因本次 `HANDOFF_READY` 改变。

## 8. 资源清理

交付前只按本任务前缀复核并清理 `review_m0_f1_mysql8_*`、`review_m0_a_fix1_mysql8_*` 容器及 `review_m0_a_fix1_mysql8_data_*` 卷，以及本 worktree 内 JVM crash/replay/临时 dump 日志；未触碰其他任务的容器、进程、端口、worktree 或日志。Docker Desktop 在任务开始时原本运行，环境恢复后保持运行。
