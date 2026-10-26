# A-FIX4 — DB unavailable bounded failure R1

## 1. 结论与范围

`PASS / HANDOFF_READY`。

- 基线：`cb1ded9b9dfcf762e28a1c1064bd58f85e40b957`（失败集成候选）。
- 分支：`fix/review-m0-a-fix4-db-unavailable-r1`。
- 产品与自动测试提交：`7e325051fd1fc6c4671a53d16fdc124aa69cbaba`。
- 最终交付 SHA：由包含本 handoff 的分支 HEAD 标识；最终回报给协调者。
- 只修改 Review M0 数据库故障分类、有界重试、对应数据源获取边界及测试；未修改 direct-store MySQL 门、事务所有权、冻结 API 字段、owner 隔离、快照、LOCAL_ONLY、日志脱敏策略、Web/UI、P08/P09 或其他阶段产品实现。
- 未运行完整 A、修复集成或全量集成回归；未启动 B/C/D；未调用远程模型或远程 MCP。

## 2. 基线复现与根因

用基线 fat Jar 和专用真实 MySQL 8 启动至健康，先完成一次 201 写入，再停止数据库。新写请求在客户端 20,112 ms 观察门内仍没有响应；终止应用后请求才释放。这不是客户端取消造成的通过证据。

根因是 A-FIX1 的 `ReviewM0ApiIdempotency.isTransientContention` 把任意 Spring `TransientDataAccessException` 或 JDBC `SQLTransientException` 都视为锁竞争。数据库下线时，实际异常链中的连接获取失败最终包含 `SQLTransientConnectionException`，于是每次连接池等待后又进入最多 24 轮新的 `REQUIRES_NEW` 事务重试。基线公共连接获取超时为 10 秒，连接池等待、事务重试与退避叠加，远超验收上界。

复现和结果记录只保存耗时、HTTP 状态、稳定错误码、requestId 是否存在及安全分类计数；没有记录异常 message、SQL、连接 URL、凭据、JWT、objectKey、hash/digest、请求/响应 body 或堆栈。

## 3. 异常分类与行为

分类先扫描完整 cause chain，`STORAGE_UNAVAILABLE` 优先级高于嵌套的 contention 标记。

| 分类 | 明确信号 | 行为 |
| --- | --- | --- |
| `CONTENTION` | `CannotAcquireLockException`、`CannotSerializeTransactionException`、Spring `ConcurrencyFailureException`；SQLState `40001`；MySQL 1205/1213 | 只对这些信号启动新的完整 `REQUIRES_NEW` 事务重试，同时受次数和总等待预算约束 |
| `STORAGE_UNAVAILABLE` | `CannotGetJdbcConnectionException`、`CannotCreateTransactionException`、`DataAccessResourceFailureException`、JDBC transient/non-transient connection、recoverable connection、SQLState `08xxx` | 不进入多轮 contention 重试；稳定返回 HTTP 503 + `REVIEW_M0_STORAGE_UNAVAILABLE` + requestId，`retryable=true`，不暴露底层错误 |
| `OTHER` | 完整性冲突、Review 业务/载荷/幂等冲突、未知运行时错误，以及未列入白名单的 SQL 异常 | 不由幂等包装器误重试；继续走既有业务/通用异常映射 |

`IdempotencyInProgressException` 仍保留 A-FIX1 的跟随者有界重读语义。权威 DB claim、异载荷 409、不可变响应快照、胜出者回滚无残留及重启精确重放未改变。

## 4. 配置默认值和公共影响

| 配置 | 默认值 | 边界/影响 |
| --- | ---: | --- |
| `AI_DB_CONNECTION_TIMEOUT_MS` | `5000` ms | 同时用于 default/local 的 ai-service 公共 Hikari 数据源；健康连接语义不变，池耗尽或数据库不可达时由原 10 秒缩短为 5 秒失败，因此定向回归覆盖 Conversation/Memory JDBC |
| `AI_REVIEW_M0_IDEMPOTENCY_MAX_ATTEMPTS` | `24` | 仅 Review M0 完整事务尝试数，合法范围 1..64 |
| `AI_REVIEW_M0_IDEMPOTENCY_TOTAL_WAIT_BUDGET` | `8s` | 仅 Review M0 contention 总预算，必须大于 0 且不超过 10 秒 |
| `AI_REVIEW_M0_IDEMPOTENCY_TRANSACTION_TIMEOUT` | `8s` | 每轮 Review M0 新事务超时，必须大于 0 且不超过总预算 |

这些值是安全运行边界，不是产品 SLA。LOCAL_ONLY/验收默认值下，连接获取最多等待 5 秒；存储不可用一经分类即终止，不再乘以 24 轮重试。若部署方覆盖公共连接超时或数据库驱动无视事务/语句超时，必须重新验证 15 秒门。

## 5. 自动测试与冻结回归

| 门 | 结果 | 证据范围 |
| --- | --- | --- |
| A-FIX4 专项 | `26/26`，0 failure/error/skip | 启动 DB 不可用、运行中 DataSource shutdown、真实 Hikari 单连接池耗尽、连接获取仅一次、总预算、完整性不重试、分类优先级、503 envelope/requestId、非法配置和日志配置 |
| A-FIX1 精确选择器 | `62/62`，0 failure/error/skip | 原 59 项 + 本任务同类 3 项；完整新事务 contention retry、跟随者、快照、回滚语义未退化 |
| A-FIX2 精确选择器 | `40/40`，0 failure/error/skip | F4-R1、Review/Conversation envelope 和安全日志未退化 |
| 默认 Review 门 | `26/26`，0 failure/error/skip | `ASSET_VERIFIED`；扫描 1,213 个文本文件，敏感模式 0 |
| M0-01 + Agent/Conversation/JDBC 冻结选择器 | `100 discovered / 87 passed / 0 failure / 0 error / 13 expected skip` | 含 `CurrentJdbcMigrationBaselineTest`、`CurrentStructuredApiBaselineTest`、`ConversationControllerTest`、`JdbcConversationStoreTest`；首次缺 parser classpath 时失败，按冻结前置生成后原命令通过，未以缺环境判 PASS |
| Hikari 有界/泄漏自动门 | `3/3`，0 failure/error/skip | 池耗尽在 250 ms 获取边界内结束；持有连接期间 active/total 均保持 1，无额外连接，try-with-resources 后关闭数据源 |

## 6. 真实 MySQL 8 与 fat Jar 证据

### 6.1 正常数据库

真实 MySQL 8 concurrent runner 使用两个独立 datasource/事务管理器/store/wrapper：seed `1/1`、restart verify `1/1`，均 0 failure/error/skip。它验证真正锁竞争/deadlock 注入在新的完整事务中重试，以及跟随者、胜出者回滚接管、异载荷 409、owner 隔离和数据库/应用对象重启后的精确重放。

同一结果源码 fat Jar 的正常 HTTP 并发结果：

| 同键并发 | HTTP 201 | HTTP 500 | HTTP 503 | distinct body |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 8 | 0 | 0 | 1 |
| 16 | 16 | 0 | 0 | 1 |
| 48 | 48 | 0 | 0 | 1 |

### 6.2 健康启动后主动停库

再次使用独立无卷真实 MySQL 8、结果 fat Jar、生产 JWT filter 和 30 秒 HTTP 客户端：健康 canary 为 201（1,023 ms），随后显式停止 MySQL。三个全新写请求均由服务端完成响应，客户端没有取消：

| 请求 | HTTP | errorCode | requestId | details | 服务端完成耗时 |
| ---: | ---: | --- | --- | ---: | ---: |
| 1 | 503 | `REVIEW_M0_STORAGE_UNAVAILABLE` | present | 0 | 5,150 ms |
| 2 | 503 | `REVIEW_M0_STORAGE_UNAVAILABLE` | present | 0 | 5,029 ms |
| 3 | 503 | `REVIEW_M0_STORAGE_UNAVAILABLE` | present | 0 | 5,036 ms |

分布：min 5,029 ms、p50 5,036 ms、max 5,150 ms；503=`3`，500=`0`，全部小于 15 秒。每次安全诊断均为 `category=DATABASE`、`retryable=true`、`retryCount=0`、`count=1`。

## 7. 日志扫描

受控结果 fat Jar stdout/stderr 共 2 个文件、48 行：

| 禁止内容 | 计数 |
| --- | ---: |
| SQL | 0 |
| Java stack-frame | 0 |
| Throwable header | 0 |
| connection URL | 0 |
| credential/password/Authorization | 0 |
| JWT | 0 |
| objectKey | 0 |
| hash/digest | 0 |
| request/response body | 0 |
| generated password | 0 |

保留了 3 条存储不可用安全诊断，只含稳定 event/reasonCode、requestId/correlationId、category、retryable、retryCount、count；未输出异常 message 或堆栈。

## 8. 与 A-FIX3 的冲突提示

检查 A-FIX3 当前 SHA `4dea8e63150a0bcc7a8849fd6585fec5f86f9d7a` 相对共同基线的文件列表：生产文件与本任务没有重叠；唯一确定的文件级重叠是：

- `ai-service/src/test/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiIdempotencySnapshotTest.java`

集成时应手工保留双方新增测试，不可用整文件覆盖。若 A-FIX3 后续修改 `ReviewM0ApiIdempotency`、分类器、运行配置或公共 YAML，必须重新审计事务所有权和 15 秒门；本任务没有改 A-FIX3 专属 direct-store runner 或 store 事务所有权。

## 9. 剩余风险与清理

- 公共 Hikari 5 秒连接获取边界影响 ai-service 全部 JDBC 消费者；冻结 Conversation/Memory/JDBC 定向回归已通过，但没有在本任务内运行完整 Memory Integrated 或完整集成回归。
- 总预算不能强制中断完全无视 JDBC/事务超时的第三方驱动；当前 MySQL Connector/J、Hikari 获取边界和 Spring 事务超时组合已由真实停库与池耗尽路径验证。
- 没有把真实生产网络分区、超大连接池或长时间压力测试推断为已验证。
- 本任务启动的应用进程、监听端口、MySQL 容器和无卷网络资源均已停止/移除；临时脚本、日志、报告及 JVM crash/replay 文件在最终提交前按独立 worktree 绝对边界清理，不触碰其他任务资源。
