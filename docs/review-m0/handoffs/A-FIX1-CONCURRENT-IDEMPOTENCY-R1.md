# Review M0 A-FIX1：真实 MySQL 并发幂等修复交接

## 1. 结论与隔离

- 判定：`PASS / HANDOFF_READY`。
- 固定代码基线：`741a73bd2370a271c0386f3128a48077e55c95fc`。
- 实现结果 SHA：`6d9066ca9398487a1697d609d033dc0897beb7cf`。
- 本 handoff 所在最终文档提交无法在自身内容中稳定自引用；最终分支 HEAD 由交付回执给出。
- 分支：`fix/review-m0-a-fix1-idempotency-r1`。
- worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-a-fix1-idempotency-r1`。
- 失败证据 `9f4bc5a449e4ca0ab3d773697ba6bdf23ef89b2a` 只用于读取 `M0-R4-MYSQL8-R1.md`，不是代码祖先或实现输入。

本任务只修改 Review M0 并发幂等事务边界、内部跟随信号及其测试。没有修改默认日志策略、API envelope、迁移、公共配置、`docs/PROGRESS.md`、Memory/MCP/Qdrant、文件/文档/远程模型路径；没有运行完整 A，也没有启动 B/C/D。

## 2. 已确认根因

原实现存在两个相互放大的事务边界问题：

1. `ReviewM0ApiIdempotency` 已持有包含 claim、业务写和首次响应快照的外层事务；`JdbcReviewM0IdempotencyStore.claim` 又以默认 `REQUIRED` 加入同一事务，并在存储方法内部捕获瞬态锁竞争后短重试。
2. MySQL 死锁或锁等待类异常发生时，参与中的事务已经被标记回滚；内层循环却继续复用该事务。即使后续查询得到胜出记录，外层提交仍可能失败，并被通用异常路径映射成 500。
3. 已读到 `IN_PROGRESS` 的同载荷跟随者立即返回 503，没有离开当前事务后有界等待并重读胜出者 `COMPLETED` 快照。

该根因与 R4-A-R1 的现象一致：数据库最终只有一份业务事实，但少数客户端在事务提交阶段收到内部 500。根因确认来自生产调用链审计、回滚标记语义测试、真实 MySQL 双实例竞争和同一 fat Jar 的真实 HTTP 复现/修复后重跑；没有依赖原始异常文本或运行堆栈作为交接证据。

## 3. 修复设计

- DB 继续是跨进程唯一权威；未增加 JVM 本地锁或本地 claim 缓存。
- API 幂等包装器使用 `REQUIRES_NEW` 拥有每次完整原子尝试；瞬态 DB 竞争发生后，整笔 claim、业务写、快照完成事务回滚，下一次尝试在全新事务执行。
- JDBC store 删除同一参与事务中的瞬态短重试，只执行一次权威 claim；唯一键命中后仍校验完整 owner/method/path/key 和载荷摘要。
- `REPLAY_IN_PROGRESS` 改为内部跟随信号；同步、动态和 delegated Job 三条入口均离开当前事务后退避并重读。最多 24 次，退避为带抖动的 2–100 ms，纯退避总上限小于 1.9 秒；数据库自身锁等待仍受 MySQL 锁超时约束。
- 重试耗尽或线程中断统一抛出既有 `REVIEW_M0_STORAGE_UNAVAILABLE`，由冻结错误映射返回 503，不落入通用 500。
- 首次业务写、首次成功状态和不可变响应快照仍在同一事务；失败尝试不留下 claim、业务行或快照，后续实例可以接管。
- 相同 key 异载荷继续 409；scope 摘要命中后继续完整字段复核，碰撞继续 fail closed。

## 4. 自动与真实验收矩阵

计数顺序均为 discovered / passed / failures / errors / skipped。

| 场景 | 环境 | 结果 |
| --- | --- | --- |
| 新事务完整重试、跟随者等待、耗尽稳定错误、顺序快照 | H2 MySQL-mode + Spring 事务 | 首轮相关测试 `18 / 18 / 0 / 0 / 0` |
| API 契约、owner 隔离、CAS、顺序/并发幂等 | H2 MySQL-mode | 最终相关回归的一部分，全部通过 |
| delegated Job 幂等、相同输入唯一 Job/不可变 envelope | H2 MySQL-mode | Formal + Wave3 recheck 全部通过 |
| 取消恢复 | H2 MySQL-mode | `5 / 5 / 0 / 0 / 0` |
| 报告唯一发布、不可变、异主隐藏、不可恢复 current | H2 MySQL-mode | report 两类 `11 / 11 / 0 / 0 / 0` |
| 相关组合回归总计 | H2 MySQL-mode | `59 / 59 / 0 / 0 / 0` |
| 8/16/48 路同 owner/scope/key/载荷 | MySQL 8.0.46；两个独立 DataSource/事务管理器/store/wrapper | 每批只有 1 次业务写、1 条完成记录、1 份快照；所有调用状态和值一致 |
| 瞬态竞争注入 | MySQL 8.0.46 | 在完成阶段注入等价锁竞争；首事务完整回滚，第二个新事务成功，最终三类记录各 1 |
| 已提交 `IN_PROGRESS` 跟随 | MySQL 8.0.46；双实例 | 跟随者未执行业务操作，等待后精确读取胜出快照 |
| 胜出者回滚后接管 | MySQL 8.0.46；双实例 | 失败后 claim/业务/快照均为 0；另一实例同 key 成功接管 |
| 异载荷与 owner 隔离 | MySQL 8.0.46；双实例 | 同 owner 异载荷 409；不同 owner 同 key 相互隔离 |
| 应用对象与 MySQL 重启 | MySQL 8.0.46 持久卷 | 新 store/wrapper 与 MySQL restart 后状态 201、响应字节精确重放，业务操作 0 次 |

真实 MySQL 自动 runner 分 seed/verify 两个进程阶段，各为 `1 / 1 / 0 / 0 / 0`，没有跳过。它在 seed 阶段执行全部 8/16/48、双实例、竞争注入、回滚接管、冲突/owner 测试；重启 MySQL 后由新的测试 JVM 执行 verify。

## 5. 真实 HTTP 计数

使用结果源码构建的 `ai-service-1.0.0.jar`、真实 MySQL Community Server 8.0.46、生产 JWT filter、JDK/.NET 原生 HTTP 客户端。远程模型和 MCP 均关闭，Memory、Rabbit listener、Nacos discovery 和调度均关闭。

| 并发规模 | HTTP 201 | HTTP 500 | HTTP 503 | 不同响应字节数 |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 8 | 0 | 0 | 1 |
| 16 | 16 | 0 | 0 | 1 |
| 48 | 48 | 0 | 0 | 1 |

- 三个并发 key 完成后：案例/幂等完成记录/响应快照为 `3 / 3 / 3`。
- 48 路 key 使用不同规范载荷再请求：HTTP 409。
- 停止应用、重启 MySQL、重新启动同一结果 Jar 后再次请求 48 路 key：HTTP 201，响应字节与首次完全相同。
- 最终有效 HTTP 运行没有 500/503；没有保存或提交认证值、请求/响应正文、数据库语句或异常堆栈。

HTTP 专项使用忽略目录中的一次性 runner；运行后已删除 runner、stdout/stderr、认证材料、应用进程、监听端口、专用容器和专用卷。可复现的核心 DB/双实例/重启自动 runner 已提交在 `ai-service/src/test/resources/review-m0/mysql8/run-real-mysql8-concurrent-idempotency.ps1`。

## 6. 实际命令

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=ReviewM0ApiIdempotencySnapshotTest,JdbcReviewM0IdempotencyStoreTest,ReviewM0JobSubmissionServiceTest' test

powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8-concurrent-idempotency.ps1

.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=ReviewM0ApiIdempotencySnapshotTest,JdbcReviewM0IdempotencyStoreTest,ReviewM0JobSubmissionServiceTest,ReviewM0ApiContractTest,ReviewM0CancellationRecoveryTest,FormalReviewIdempotencyRegressionTest,Wave3RecheckIdempotencyAndIntegrityTest,ReviewReportAndDecisionTest,JdbcReviewReportStoreTest' test

.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am -DskipTests package
```

另执行一次忽略目录临时 HTTP runner，命令为 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\target\a-fix1-http.ps1`；该 runner 及运行日志已在验收后删除。PowerShell runner 解析检查和 `git diff --check` 均为 0 error。

## 7. 修改文件

生产：

- `ai-service/src/main/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiIdempotency.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/IdempotencyInProgressException.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/JdbcReviewM0IdempotencyStore.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/job/ReviewM0JobSubmissionService.java`

测试/交接：

- `ai-service/src/test/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiIdempotencySnapshotTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/api/ReviewM0RealMysql8ConcurrentIdempotencyIT.java`
- `ai-service/src/test/resources/review-m0/mysql8/run-real-mysql8-concurrent-idempotency.ps1`
- `docs/review-m0/handoffs/A-FIX1-CONCURRENT-IDEMPOTENCY-R1.md`

## 8. 风险、集成顺序与潜在冲突

- 退避预算是安全技术值，不是产品 SLA。极端长事务、连接池耗尽或数据库不可用仍会按既有存储 503 fail closed；正常 8/16/48 验收没有耗尽。
- 本实现只对 Spring/SQL 明确标记的瞬态异常重试；永久约束错误、载荷冲突、快照完整性失败和业务异常不会被误重试。
- `REQUIRES_NEW` 明确让 API 幂等包装器拥有原子边界；若未来在其外部新增业务事务，外部事务不能再假定与 Review 写共同提交，必须先重新审查冻结原子性。
- A-FIX2 负责日志默认策略；本提交没有修改 exception handler 或 logging 配置，与 A-FIX2 预期无直接路径冲突。
- 与任何同时修改 `ReviewM0ApiIdempotency`、`JdbcReviewM0IdempotencyStore` 或 `ReviewM0JobSubmissionService` 的后续修复存在语义冲突风险，不能机械择一。

建议集成顺序：

1. 在唯一基线 `741a73bd2370a271c0386f3128a48077e55c95fc` 上 cherry-pick 本分支最终提交；
2. 再集成不改变上述三类事务语义的 A-FIX2；
3. 先复跑本 handoff 的 59 项相关回归和真实 MySQL runner；
4. 由协调者重新完整执行 R4-A，只有 A 全部通过后才能授权后续严格阶段。

没有 schema 或 API 兼容迁移要求。没有运行完整 A，不从本任务局部 PASS 外推 `HUMAN_ACCEPTED` 或后续阶段已授权。
