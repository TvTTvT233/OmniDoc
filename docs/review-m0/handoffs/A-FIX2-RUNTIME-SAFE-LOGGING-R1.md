# A-FIX2 Runtime Safe Logging R1 交接

## 1. 结论与拓扑

- 结论：`PASS / TARGETED_VERIFIED / INTEGRATION_PENDING`。
- 固定代码基线：`741a73bd2370a271c0386f3128a48077e55c95fc`（`integration/review-m0-r4-fixes-r1-01a04`）。
- 失败证据提交：`9f4bc5a449e4ca0ab3d773697ba6bdf23ef89b2a`，仅阅读 handoff，没有作为代码基线。
- 分支：`fix/review-m0-a-fix2-runtime-logging-r1`。
- worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-a-fix2-runtime-logging-r1`。
- 实现结果 SHA：`eb57b799fbc8558f392bc0f6cdb67deef2b340c0`。
- 本文档提交的最终 SHA 无法在自身内容中稳定自指，由最终交付消息给出。
- 未修改主 checkout、其他 worktree、`milestone/review-m0`，未生成 diff/patch 文件，未运行完整 A、B、C、D 或 Integrated 全量门。

## 2. 实际根因

固定基线 fat Jar 在 LOCAL_ONLY 类配置、Redis 未提供、AIOps 周期缩短的复现中，7 秒产生 567 行日志，其中 Java stack-frame 343、Throwable header 7、调度器 `Unexpected error` 7、DEBUG 3。主要原因是：

1. 默认和 local 配置把 `com.javaee.aiservice` 设为 DEBUG；
2. `MonitoringService` / `FaultDetector` 的 `@Scheduled` 默认启用，Redis 故障逃逸至 Spring scheduler，框架记录完整异常对象；
3. Spring JDBC、事务、ORM 和连接池没有显式日志边界；
4. Review、Conversation、公共全局异常处理以及若干后台/降级路径仍把 Throwable 或 `getMessage()` 交给日志框架；
5. 少数旧 INFO 日志直接记录 prompt、查询、大纲、生成内容、远程响应或告警对象。

上游 A 失败证据中的 80 条 SQL 和 1,563 条 Java stack-frame 与上述默认等级及未约束异常展开一致。本任务还在迭代 fat Jar canary 中复现了客户端中断落入公共 `GlobalExceptionHandler` 后展开异常，因而公共 handler 也纳入日志修复，但返回行为保持原样。

## 3. 实现

### 3.1 安全诊断事件

- 新增 `SafeDiagnosticLogger`，固定输出：`event`、`reasonCode`、`requestId`、`correlationId`、白名单 `exceptionCategory`、`retryable`、`retryCount`、`count`。
- event/reason/request/correlation 只允许长度受限的安全 token；异常类别仅允许 `DATABASE`、`REDIS`、`TIMEOUT`、`AUTHENTICATION`、`CONCURRENCY`、`VALIDATION`、`IO`、`REMOTE_SERVICE`、`SYSTEM`。
- 不记录 Throwable、异常 message、连接地址、请求/响应正文或远程响应。
- Review request filter 在请求期间设置并清理 MDC；Review、Conversation、Security 和公共全局异常处理使用安全结构化事件。
- 既有异常继续抛出，既有 `Result`、HTTP 状态和 Review/Conversation envelope 构造未改。

### 3.2 配置与非必要依赖

- 默认和 local 日志收紧为 root/ai-service INFO；MCP 日志关闭；Hibernate SQL 关闭；MyBatis、Spring JDBC/事务和 Hikari 至少 WARN。
- `logging.exception-conversion-word="%nopex"` 作为 fat Jar 防御层，阻止遗漏的旧 Throwable 被控制台 pattern 展开为堆栈；不改变 Java 异常对象、控制流或 HTTP 行为。
- AIOps 调度改为 `AI_AIOPS_SCHEDULING_ENABLED=true` 时才启用，默认 false；AIOps REST/内存指标能力不受影响。
- 新 `AiOpsScheduler` 包裹指标持久化和故障检测，失败会计数并记录安全事件，不让异常逃逸给 Spring scheduler。
- Redis/local vector 降级、异步任务、RAG/Agent/模型和 PPT 旧日志移除 Throwable、异常 message、prompt/query/body/remote response 等原值，仅保留长度、计数、模型名或固定事件。

### 3.3 公共影响面

- `common/GlobalExceptionHandler` 的所有消费者都会从“原始业务/校验/token message 或 Throwable”日志改为固定结构化事件和进程内 reason 计数。
- handler 返回的 code/message、方法签名和异常匹配顺序没有改变；因此这是日志可观察性变化，不是 API envelope 变化。
- `application.yml` / `application-local.yml` 是 ai-service 公共配置热点；集成时必须保留 INFO、SQL guards、`%nopex` 与 AIOps opt-in 四组设置。

## 4. 验收矩阵

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 新增安全日志/调度/公共 handler 测试 | PASS | 最终 10/10，failures/errors/skips 均 0 |
| F4-R1 + Review/Conversation/结构化 API 合同 | PASS | 与新增测试合并最终 40/40，failures/errors/skips 均 0 |
| M0-01 冻结精确选择器 | PASS | 100 discovered / 87 passed / 13 expected skipped / 0 failure / 0 error |
| 默认 Review asset gate | PASS | 26/26，`ASSET_VERIFIED`，1202 个文本文件敏感扫描 0 命中 |
| fat Jar 正常启动/HTTP | PASS | 两次 `Tomcat started`；`/api-docs` 200；generated security password 0 |
| Redis 未配置/不可用 + 调度异常 canary | PASS | 78 行，13 条安全诊断；AIOps Redis 故障未逃逸 scheduler；未认证请求保持 403 |
| AIOps 默认关闭 | PASS | MySQL fat Jar 显式保持默认 false，AIOps 调度失败事件 0；注解/配置测试验证 missing property 不匹配 |
| MySQL 8 正常启动与 schema | PASS | 临时独占 MySQL 8；Review schema ready 1 |
| DB 并发 | PASS（仅日志目标） | 4 路不同幂等键并发：3×201、1×既有 503；安全类别 `CONCURRENCY`，未修改 A-FIX1 算法 |
| DB 不可用 | PASS（仅日志目标） | 停库后写请求保持既有 500 / `REVIEW_M0_INTERNAL_ERROR`；安全类别 `DATABASE`，未改状态/envelope |
| 缺失资源 | PASS | 有效 JWT 的不存在 case 保持 404 |

M0-01 运行中的 malformed document characterization 会由 document-service 测试类打印其既有负向堆栈；这不是 fat Jar 最终日志。ai-service fat Jar 已由 `%nopex` 防御层和最终运行扫描证明无堆栈。

## 5. 最终运行日志扫描

最终仅扫描两份本任务 fat Jar 日志，合计 2 文件 / 127 行：

| 字段 | 计数 |
| --- | ---: |
| SQL | 0 |
| Java stack-frame | 0 |
| Throwable header | 0 |
| connection URL | 0 |
| credential | 0 |
| JWT | 0 |
| objectKey | 0 |
| hash/digest | 0 |
| body | 0 |
| generated security password | 0 |
| 安全结构化 diagnostic | 16 |

分组计数：Redis/调度日志 78 行、diagnostic 13；MySQL 并发/停库日志 49 行、diagnostic 3。扫描入口为 `scripts/review-m0/run-a-fix2-runtime-log-scan.ps1`，任何禁项非零即退出 1。

## 6. 验证环境噪声与清理

- 受限 Windows 主机曾有两次页文件/native memory 噪声：一次测试 JVM 在执行测试前失败（0 tests），一次 `-am` 编译 JVM 生成 crash 文件。最终使用非 fork 测试 JVM完成全部上表验证；这些早期失败不计作通过证据。
- 本任务启动的两个 Java 进程均已停止；唯一临时 MySQL 容器 `omnidoc_afix2_mysql_20260829_0433` 已按 ID/name/status 精确核对后删除；没有命名卷或网络。
- 早期失败日志、最终含运行配置的临时日志和 JVM crash/replay 文件均在交付前删除；不触碰其他任务资源。

## 7. 风险、集成顺序与冲突

1. 先集成 A-FIX1，再集成本 A-FIX2；A-FIX1 独占并发幂等算法。本任务只记录了现有并发结果，没有修复、规避或重写该算法。
2. 本任务依赖 F4-R1 已恢复的 HTTP/异常行为；解决 `ReviewM0ApiExceptionHandler`、`ReviewM0RequestIdFilter` 冲突时必须保留本任务安全日志，同时以 A-FIX1/F4-R1 的控制流和 envelope 为准。
3. 高冲突文件：两个 `application*.yml`、Review exception handler/filter、`common/GlobalExceptionHandler`，以及 Agent/RAG 的日志行；不得用冲突解决重新引入 Throwable/message/body。
4. AIOps 后台持久化/检测现在默认关闭。需要该运维能力的环境必须显式设置 `AI_AIOPS_SCHEDULING_ENABLED=true` 并提供 Redis；这是 LOCAL_ONLY/验收降噪的有意配置影响。
5. `%nopex` 会让 ai-service 中未迁移旧 logger 的堆栈也不显示。新增故障路径应接入安全 reason code/类别/计数，而不是重新开启异常展开。
6. 公共 handler 日志变化会影响其他服务的日志查询字段，集成方应同步 dashboard/告警查询；响应行为无需迁移。
7. 本任务没有运行完整 A、Integrated、真实 MinIO/OCR/远程模型/MCP/浏览器；不得据此声明这些链路通过。
