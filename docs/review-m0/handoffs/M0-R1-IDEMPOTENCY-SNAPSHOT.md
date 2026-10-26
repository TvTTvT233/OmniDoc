# Review M0 R1-A：首次成功 HTTP 响应不可变幂等快照交付

## 1. 基线、分支与边界

- 固定基线：`eb400ee3c9c5ee06afc82e600a5e673a4c4d88a9`
- 分支：`feature/review-m0-r1-a-idempotency-01a02df0`
- 独立 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-r1-a-idempotency-01a02df0`
- 实施提交：本文件所在的单一提交；完整 SHA 以交付消息及该 worktree 的 `git rev-parse HEAD` 为准。Git 提交无法在自身内容中可靠自引用其最终 SHA。
- 仅实现 R1-A：持久化首次成功 HTTP status/body 不可变快照及其严格重放。未处理取消恢复、file-service、M0-07 验收资产、P-08/P-09。
- 未修改 `job/**`、Review file/archive/material、`acceptance/**`、`scripts/review-m0/**`、公共 `application*.yml`、根 `pom.xml`、`docs/PROGRESS.md`、Web/UI、Agent/MCP/Qdrant/Memory 代码。

基线提交存在性、共享 checkout 状态和独立 worktree 均在实现前验证。实现前完整读取了任务要求列出的仓库说明、冻结契约、任务所有权、验收契约、README 及 M0-06/M0-07A handoff。

## 2. 实现结论

首次成功 HTTP 响应现在会在同一业务事务中序列化并持久化：

- 快照格式版本固定为 `1`；UTF-8 响应体硬上限为 `262144` 字节。
- 摘要绑定格式版本、HTTP status、响应类型、响应 ID 与完整 body；另以 owner/method/path/key 计算 scope digest，并在读取时复核完整 scope，防止摘要碰撞被误认。
- 正常重放只反序列化持久化快照，不再按资源 ID 读取当前可变状态；进程内实例重建后仍得到第一次的逐字段 status/body。
- 未知格式、字节数不符、超限、摘要不符、scope 不符、响应类型/ID 不符，以及旧完成记录缺少快照时均 fail closed 为稳定 503，不拼接数据，也不伪装成严格重放。
- 同 key 不同规范客户端载荷仍为 409；scope 仍是 owner + case + endpoint + `Idempotency-Key`。
- 同步 API 在一个事务中 claim、业务写、快照完成；Job 提交由外层 API 事务包裹既有提交服务，并在提交前附加首次 202 body。回滚或前置校验失败不会占 key，也不会留下半完成快照。
- 保留既有 store `complete(...)` 兼容入口，避免破坏上游直接调用；严格 HTTP 重放只接受带快照的完成记录。
- 结构化敏感字段检查拒绝文件字节、原始正文、`objectKey`、预签名 URL、凭据、token、cookie、私钥、堆栈等字段进入快照；没有新增含响应正文或敏感内容的日志。
- V003 采用旁路向前迁移表，不修改或破坏 V001/V002；自动迁移时幂等创建，手工迁移模式缺表时启动 fail closed。

## 3. 冻结行为逐项验收

| # | 要求 | 状态 | 证据 |
| ---: | --- | --- | --- |
| 1 | 同事务保存首次成功 status/body；重放不读当前资源 | PASS | API 契约测试在首次响应后直接改变 case/job 当前状态，重放 JSON 仍与首次响应逐字段相同；实现删除 replay loader |
| 2 | 版本、大小上限、完整性摘要；异常 fail closed | PASS | 覆盖初次超限回滚、持久化后篡改、未知版本、持久化超限；均不返回当前资源或拼接 body |
| 3 | scope 不变；资源变化/重启后严格重放；异载荷 409 | PASS | 覆盖资源变化、重新构造 API/store 模拟重启、不同规范载荷冲突 |
| 4 | 并发唯一业务写/快照；验证失败和回滚不占 key | PASS | 8 路并发仅一次业务写且仅一条快照；操作异常后 base/snapshot 均为 0，可用同 key 重试 |
| 5 | 不持久化敏感内容；错误四字段与旧 API 兼容 | PASS | `objectKey` 结构字段被拒绝并整体回滚；既有 API/config 契约 23/23 通过；未改错误 DTO/handler |
| 6 | 旧完成记录无快照的保守兼容 | PASS | store 旧入口仍可读；HTTP 严格重放稳定 503，测试断言不调用当前资源 loader/业务操作 |

最低验收测试均已覆盖：资源变化后重放、重启后重放、不同载荷冲突、并发一次写、事务回滚、快照篡改/未知版本/超限、旧记录无快照，以及敏感字段拒绝。

## 4. 修改文件

生产代码与迁移：

- `ai-service/src/main/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiIdempotency.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiService.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/IdempotencyRecord.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/IdempotencyResponseSnapshot.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/JdbcReviewM0IdempotencyStore.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/ReviewM0IdempotencySnapshotSchemaInitializer.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/ReviewM0IdempotencyStore.java`
- `ai-service/src/main/resources/db/review-m0/V003__review_m0_idempotency_response_snapshot.sql`

测试与交付：

- `ai-service/src/test/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiContractTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiIdempotencySnapshotTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/idempotency/ReviewM0IdempotencySnapshotSchemaInitializerTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/store/ReviewM0JdbcTestFixture.java`
- `docs/review-m0/handoffs/M0-R1-IDEMPOTENCY-SNAPSHOT.md`

## 5. 实际命令与结果

计数顺序为 discovered / passed / failures / errors / skipped。

| 验证项 | 实际命令或 selector | 结果 |
| --- | --- | --- |
| 固定基线 | `git cat-file -e eb400ee3c9c5ee06afc82e600a5e673a4c4d88a9^{commit}`；`git rev-parse HEAD` | PASS；HEAD 等于固定基线 |
| M0-02 + M0-06 最终合并复跑 | `.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service "-Dtest=model.*Test,store.*Test,job.*Test,idempotency.*Test,api.*Test,config.*Test" test` | `89 / 89 / 0 / 0 / 0` |
| 新增快照 API 测试 | 上述最终复跑中的 `ReviewM0ApiIdempotencySnapshotTest` | `6 / 6 / 0 / 0 / 0` |
| 既有 idempotency store | 上述最终复跑中的 `JdbcReviewM0IdempotencyStoreTest` | `4 / 4 / 0 / 0 / 0` |
| 新增 V003 初始化器 | 上述最终复跑中的 `ReviewM0IdempotencySnapshotSchemaInitializerTest` | `2 / 2 / 0 / 0 / 0` |
| M0-06 API/config | 上述最终复跑中的 `api.*Test,config.*Test` | `23 / 23 / 0 / 0 / 0`（API contract 13、snapshot 6、config 4） |
| Wave2 + Wave3 + Wave3 recheck | `.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service "-Dtest=com.javaee.aiservice.review.m0.integration.wave2.*Test,com.javaee.aiservice.review.m0.integration.wave3.*Test,com.javaee.aiservice.review.m0.integration.wave3recheck.*Test" test` | `42 / 42 / 0 / 0 / 0`（6 + 23 + 13） |
| ai-service compile | `.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am -DskipTests compile` | BUILD SUCCESS |
| 默认 Review asset gate | `scripts\review-m0\run-m0-acceptance.cmd` | `26 / 26 / 0 / 0 / 0`；敏感数据扫描 1153 个文本文件无命中；`ASSET_VERIFIED`；exit 0 |
| 差异格式 | `git diff --check`；暂存后 `git diff --cached --check` | 工作区检查 PASS；暂存检查待提交前执行 |

另有一次无效诊断命令使用了缺少 `integration` 段的 Wave 包 selector，Maven 因 `No tests matching pattern` 退出 1；这是 selector 错误，discovered 为 0，并非测试 failure/error。上表记录的是修正为仓库实际包路径后的有效最终复跑。

## 6. 真实运行与 NOT_RUN

真实运行：

- 本地 JDK/Maven 编译和 JUnit 5。
- H2 MySQL-mode JDBC、Spring 事务、真实 Review M0 JDBC store/schema initializer 与 API service 组合测试。
- 本地静态资产门禁和敏感内容扫描（最终结果见上表）。

以下外部依赖均为 `NOT_RUN`，不能从本交付推断其生产可用：

- 真实 MySQL 8 及线上迁移工具执行 V003。
- 真实 owner-aware file-service、MinIO 或对象存储。
- 真实 document-service、OCR、文档解析网络链路。
- 真实远程模型或供应商。
- 浏览器/UI 与 P-09 产品入口。
- 多实例跨主机并发和故障注入；本地测试覆盖多线程并发及进程实例重建语义。

## 7. 剩余风险与迁移边界

- 历史已完成记录没有首次 body，无法事后重建严格快照；本实现明确以 503 fail closed，不读取当前资源冒充首次响应。迁移不会回填伪快照。
- V003 是新增旁路表，集成/部署必须先确保自动迁移启用，或在手工迁移模式下先应用 V003；缺表会拒绝启动，避免静默降级。
- 256 KiB 是幂等响应体技术安全上限；若未来新增成功 DTO 超限，首次写会整体回滚并返回系统不可用，而不是截断快照。
- 结构字段拒绝是纵深防护，仍要求未来 API DTO 保持白名单投影，不把原始文件/正文/凭据暴露为普通字段。

未发现冻结契约冲突；没有扩大到 P-08/P-09、取消恢复或 file-service。

## 8. 集成顺序与复跑命令

1. 在包含固定基线 `eb400ee3c9c5ee06afc82e600a5e673a4c4d88a9` 的集成分支 cherry-pick 本交付单一提交；不要先合并其他 R1 任务后再凭冲突结果改写本契约。
2. 部署前先应用 `V003__review_m0_idempotency_response_snapshot.sql`，或确认 Review M0 自动迁移启用；再启动包含 API/store 代码的应用。
3. 与其他第一轮任务集成后，至少复跑：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service "-Dtest=model.*Test,store.*Test,job.*Test,idempotency.*Test,api.*Test,config.*Test" test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service "-Dtest=com.javaee.aiservice.review.m0.integration.wave2.*Test,com.javaee.aiservice.review.m0.integration.wave3.*Test,com.javaee.aiservice.review.m0.integration.wave3recheck.*Test" test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am -DskipTests compile
scripts\review-m0\run-m0-acceptance.cmd
git diff --check
```

若集成触及 idempotency schema/store/API 调用点，必须额外保留“无快照旧记录 503”“篡改/未知版本/超限 fail closed”与“Job 当前状态变化后仍重放首次 202 body”的测试语义。
