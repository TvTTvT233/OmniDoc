# Review M0 完整 A 重验 R4

## 1. 唯一结论

- 正式 A 判定：`A_PASSED`。
- 下一阶段授权：`B_AUTHORIZED`，但本任务没有启动 B、C、D，也没有合并或部署。
- 唯一产品候选：`a8e3440232f50c91b17064a5f8bbcc34267ea475`。
- 验收 runner 内容提交：`e5077489c45e63541508713bff8c5e165b6f96b5`。
- 本文件是 runner 内容提交后的纯验收证据提交；其最终 SHA 由提交后的 `git rev-parse HEAD` 给出。
- 未修改任何产品源代码、产品配置、断言或冻结 skip；没有沿用 A-R3 或本轮任何被废弃尝试的局部通过结果。

## 2. 固定输入、隔离与环境

| 项目 | 实际值 |
| --- | --- |
| 保存项目根 | `C:\Users\lab\Desktop\docAI-main`（不是 Git 仓库） |
| Git 仓库 | `C:\Users\lab\Desktop\docAI-main\OmniDoc` |
| 候选分支 | `integration/review-m0-a-fix5-r1` |
| 产品候选 | `a8e3440232f50c91b17064a5f8bbcc34267ea475` |
| validation 分支 | `validation/review-m0-r4-a-r4` |
| 独立 worktree | `C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-r4-a-r4` |
| milestone | `fa7f1d8f8b3df984db84681bd15e55bd96c9a127`，未移动 |
| 正式有效运行 | 2026-09-01 00:25:34.7648194 至 00:50:11.8747947，Asia/Shanghai |
| PowerShell | Windows PowerShell `5.1.19041.6456` |
| Java | Eclipse Temurin OpenJDK `17.0.19` |
| Maven | Wrapper Apache Maven `3.9.12` |
| Docker | client/server `29.6.2` |
| MySQL 镜像 | `mysql:8.0@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b` |
| 正式 MySQL | `8.0.46` |

validation 分支从候选 SHA 新建，创建前核对了现有分支与 worktree 名称，没有覆盖已有资源。主 checkout 没有用于开发或验收写入；milestone、候选分支及其他 worktree 均未修改。

## 3. 预检（不计入正式 A 证据）

正式 A 前完成了短预检，随后清空全部预检资源。最终预检为 `PREFLIGHT_PASS`，9 项 runner 断言通过：

| 预检项 | 结果 |
| --- | --- |
| GET/POST 与 envelope | GET 200 且请求对象无 content；POST JSON 正确；401 / `AUTHENTICATION_REQUIRED` 四字段 envelope 与 requestId 可采集 |
| 代理 | `.NET HttpClientHandler.UseProxy=false`，清除进程级代理变量，`NO_PROXY=127.0.0.1,localhost` |
| PowerShell/.NET | PowerShell parser 0 error；`System.Net.Http`、JSON、HMAC/JWT、进程 API 可用 |
| 随机端口/readiness | 预检端口 `43000,43010,43024,43051`；MySQL、file fat Jar、两个 ai fat Jar 全部 HTTP ready |
| LOCAL_ONLY/P08 | Rabbit listener、Memory、MCP、四路远程模型禁用；Redis 指向本机不可达端口；P08 `DISABLED` |
| 日志枚举 | 扫描 6 个 stdout/stderr 文件、123 行；全部禁泄漏模式 0，安全诊断 2 |
| 低内存预算 | MySQL 512 MiB、buffer pool 64 MiB、performance schema off；JVM `-Xms16m -Xmx224m` 等预算可执行 |
| 清理 | finally 精确停止 Java、删除任务 MySQL 容器与卷；预检端口 listener 0 |

预检第一次暴露 PowerShell `[string]` 将 `$null` 绑定为空字符串、从而误给 GET 附加 content 的 runner-only 问题。仅把请求 body 参数改为允许真正的 null，未改产品；清理后重新预检并通过。该预检及其修正均不计入正式证据。

预检通过后执行 Maven clean，确认预检 Jar、日志、容器、卷、进程和端口均为 0，再开始正式阶段。

## 4. 正式阶段完整性与 runner 噪声隔离

正式阶段第一次排程在产品 HTTP 断言开始前的静态 runner 审计中发现旧草稿使用了错误表名 `review_m0_idempotency_response`；产品实际冻结表名是 `review_m0_idempotency_response_snapshot`。这属于验收 SQL 的 runner-only 错误。修正两处验收查询后，废弃该次已有的 A1 局部输出，执行 Maven clean、容器/卷/进程/端口全清理，并于 00:25:34 从全新资源重跑整个 A。本文后续所有计数只来自这一次完整有效运行。

在全部必列冻结项通过后，为补充 LOCAL_ONLY 证据运行 `AllModelsTest`；第一次因未提供合成 `MINIO_ACCESS_KEY` 占位符而在 Spring 上下文加载阶段退出，尚未进入产品断言。补齐纯合成占位变量后，同一补充项完整通过 1/1。`AllModelsTest` 不属于任务列出的必跑计数门；该环境噪声不替代、也不污染正式 A 结果。

## 5. A1-A7 正式结果

### A1 MySQL 8

- 旧真实 MySQL 8 runner 连续三个独立新容器轮次：每轮 `2 / 2 / 0 / 0 / 0`，无自动重试覆盖失败。
- 新 concurrent runner：seed `1 / 1 / 0 / 0 / 0`；同一持久卷重启 MySQL 后 verify `1 / 1 / 0 / 0 / 0`。
- 正式原生门：MySQL `8.0.46`；schema 计数/最大版本 `3 / 3`，即 V001-V003。
- 冻结测试覆盖 schema v3 的表、索引、FK/check，部分 8/22 续建，V1/V2 升级，未知 shape fail-closed，旧 completed 无 snapshot，以及新 seed/verify。

结论：A1 PASS。

### A2 安全、owner、permitAll 与 CAS

- 七类缺失/无效认证全部为 `401 / AUTHENTICATION_REQUIRED`；每个响应均为精确 `details,errorCode,message,requestId` 四字段 JSON，requestId 匹配 `req_[0-9a-f]{32}`。
- 合法 owner：创建/读取 `201 / 200`；跨 owner/不存在 `404 / 404`。
- 非 Review 受保护端点匿名访问为 403；生产 `SecurityFilterChain` 精确测试 12/12，既有 permitAll 与相邻保护面无变化。
- owner/file 链、precommit、JWT 兼容、CAS 和跨 owner 无副作用由原生门、R1-C、file F3/F4、M0/FIX 选择器共同覆盖。

结论：A2 PASS。

### A3 幂等、并发、事务重试与重启重放

- 顺序重放：`201 / same-body`；异载荷同键：409；完整/长 scope 与 digest/CAS 由冻结测试通过。
- 两个真实 ai-service 实例的同键并发：

| 并发 | HTTP 201 | HTTP 500 | HTTP 503 | distinct body | case/idempotency/snapshot |
| ---: | ---: | ---: | ---: | ---: | --- |
| 8 | 8 | 0 | 0 | 1 | `1 / 1 / 1` |
| 16 | 16 | 0 | 0 | 1 | `1 / 1 / 1` |
| 48 | 48 | 0 | 0 | 1 | `1 / 1 / 1` |

- deadlock/锁竞争使用新完整事务重试；冻结测试验证失败尝试回滚且无 claim、业务写或 snapshot 残留。
- MySQL 与应用重启后：`201 / same-body` 精确重放。

结论：A3 PASS。

### A4 DB 不可用

- 健康创建 201 后主动停止 MySQL。
- 三个新请求分别在 5038、5067、5056 ms 返回 `503 / REVIEW_M0_STORAGE_UNAVAILABLE`，均由服务端在 15 秒内结束。
- HTTP 500 为 0；失败残留 case/idempotency 为 `0 / 0`；新实例在库停机时 fail-closed；恢复数据库后创建 201。
- 原生 runtime 日志未出现后台异常泄漏或持续半写。

结论：A4 PASS。

### A5 作业

M0-02、FIX1-4、M0-01 与 Integrated adapter 的冻结测试全部通过，覆盖取消/重启、lease/heartbeat/checkpoint、崩溃恢复、双 dispatcher、状态/错误/progress 一致性及远程调用 consent/CAS。

结论：A5 PASS。

### A6 报告

M0-05、FIX1 frozen selector、FIX1-4 core 与 Integrated adapter 全部通过，覆盖报告不可变、材料变化后 OUTDATED、发布失败整体回滚、job/input fingerprint、owner 隔离和重复发布。

结论：A6 PASS。

### A7 原生服务与日志

- `file-service,ai-service -am -DskipTests package` 四个 reactor module 全部成功；使用生成的两个 Spring Boot fat Jar。
- 正式原生端口：MySQL `47585`，file `47602`，ai-1 `47619`，ai-2 `47647`，重启 ai `47775,47827`；file ready 1 次，ai ready 3 次。
- 正式 runtime scanner：10 个日志文件、207 行；SQL、Java stack frame、Throwable header、connection URL、credential、JWT/token、objectKey、hash/digest、request/response body、generated security password 均为 0；白名单结构化安全诊断 7。
- `localOnly=true`，P08 `DISABLED`；四远程模型 disabled，MCP 明确未创建远程客户端，Rabbit listener disabled；未调用远程模型或远程 MCP。
- file 测试上下文本身会由 Spring 测试框架打印临时 generated password；它不属于正式 fat Jar runtime 日志。正式原生日志的该项为 0。

结论：A7 PASS。

## 6. 冻结自动回归

计数为 `discovered / passed / failures / errors / skipped`。

| 门 | 正式有效结果 |
| --- | --- |
| FIX5 生产 Security/auth/API/logging 组合 | `56 / 56 / 0 / 0 / 0`，其中 SecurityFilterChain `12/12` |
| FIX1-FIX4 core + storage envelope | `64 / 64 / 0 / 0 / 0` |
| FIX1 frozen 23-method selector | `23 / 23 / 0 / 0 / 0` |
| M0-01 | `100 / 87 / 0 / 0 / 13`；13 均为冻结 expected skip |
| 默认 acceptance | `26 / 26 / 0 / 0 / 0`；`ASSET_VERIFIED`；1222 个文本文件敏感扫描 0 |
| Integrated acceptance | `41 / 41 / 0 / 0 / 0`；`AUTO_VERIFIED` |
| adapter | `15 / 15 / 0 / 0 / 0`；15 个 P0 target 全 PASS |
| M0-02 | `77 / 77 / 0 / 0 / 0` |
| M0-03 | `21 / 21 / 0 / 0 / 0` |
| M0-04 | `83 / 83 / 0 / 0 / 0` |
| M0-05 | `50 / 50 / 0 / 0 / 0` |
| M0-06 | `38 / 38 / 0 / 0 / 0` |
| Wave2 | `6 / 6 / 0 / 0 / 0` |
| Wave3 | `23 / 23 / 0 / 0 / 0` |
| Wave3 recheck | `13 / 13 / 0 / 0 / 0` |
| R1-C ai | `14 / 14 / 0 / 0 / 0` |
| file F3/F4 superset | `28 / 28 / 0 / 0 / 0` |
| DocumentServiceImplTest | `4 / 4 / 0 / 0 / 0` |
| AllModelsTest（补充 LOCAL_ONLY） | `1 / 1 / 0 / 0 / 0`；四模型 disabled，远程成功 0/4，MCP 无远程客户端 |

所有必列冻结项目均在同一产品候选、同一次从零开始的正式运行中完整结束并通过。M0-01 控制台出现的 parser 负向测试 stack trace 是冻结断言输入，不是正式 fat Jar runtime 日志或测试失败；Surefire failure/error 均为 0。

## 7. 主要精确命令

以下命令均从 validation worktree 根执行。低内存 Maven 测试统一使用 `MAVEN_OPTS=-Xms16m -Xmx256m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss512k -Xshare:off`，选择器运行使用 `-DforkCount=1`。

```powershell
# 预检、清理、正式原生门
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests package
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\review-m0\run-r4-a-native-gate.ps1 -Mode Preflight -EvidenceDirectory ai-service\target\r4ar4-preflight-evidence
.\mvnw.cmd -s .mvn\settings-local.xml clean

# 正式 A1；旧 runner 是三个独立连续命令
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8-concurrent-idempotency.ps1

# 正式 fat Jar 与原生 HTTP / DB-down / 日志门
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests package
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\review-m0\run-r4-a-native-gate.ps1 -Mode Formal -EvidenceDirectory ai-service\target\r4ar4-formal-native-evidence

# FIX5；56/56
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=ReviewM0SecurityFilterChainTest,AiOpsSchedulerTest,GlobalExceptionHandlerSafeLoggingTest,RuntimeSafeLoggingConfigurationTest,SafeDiagnosticLoggerTest,SensitiveLoggingR1Test,ReviewM0ApiContractTest,ConversationControllerTest,CurrentStructuredApiBaselineTest,CurrentAuthenticationBaselineTest,ReviewM0StorageUnavailableEnvelopeTest' test

# FIX1-FIX4 core + storage envelope；64/64
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=ReviewM0ApiIdempotencySnapshotTest,JdbcReviewM0IdempotencyStoreTest,ReviewM0JobSubmissionServiceTest,ReviewM0ApiContractTest,ReviewM0CancellationRecoveryTest,FormalReviewIdempotencyRegressionTest,Wave3RecheckIdempotencyAndIntegrityTest,ReviewReportAndDecisionTest,JdbcReviewReportStoreTest,ReviewM0StorageUnavailableEnvelopeTest' test

# 默认 / Integrated / adapter
.\scripts\review-m0\run-m0-acceptance.cmd -ReportDirectory ai-service\target\r4ar4-review-default
.\scripts\review-m0\run-m0-acceptance.cmd -Integrated -ReportDirectory ai-service\target\r4ar4-review-integrated
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=ReviewM0IntegratedEvidenceAdapter' '-Dreview.m0.acceptance.evidence.path=target/r4ar4-review-adapter/review-m0-integrated-evidence.json' test

# M0-01 前置与精确 selector
.\mvnw.cmd -q -pl document-service -am dependency:build-classpath '-Dmdep.outputFile=target/m0-pb-runtime-classpath.txt' '-Dmdep.includeScope=runtime'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest' test

# M0-02..06、Wave；逐条运行
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.checklist.*Test,com.javaee.aiservice.review.m0.privacy.*Test,com.javaee.aiservice.review.m0.extraction.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.review.*Test,com.javaee.aiservice.review.m0.report.*Test,com.javaee.aiservice.review.m0.evidence.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.integration.wave2.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.integration.wave3.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.integration.wave3recheck.*Test' test

# R1-C、file F3/F4、Document
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am -DforkCount=1 '-Dtest=HttpOwnedFileByteSourceTest,VerifiedReviewM0FileAccessTest,ReviewM0ConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -am -DforkCount=1 '-Dtest=FileApplicationTests,OwnerAwareFilePrecommitIntegrityHttpTest,OwnerAwareFileSecurityTest,OwnerAwareFileServiceTest,OwnedFileDownloadStagingTest,OwnerAwareJwtCompatibilityR1Test,SensitiveLoggingR1Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl document-service -DforkCount=1 '-Dtest=DocumentServiceImplTest' test
```

FIX1 frozen 23-method selector使用 `M0-R3-FINAL-GATE`/Wave handoff 的原精确方法列表执行，结果 23/23；未用类级宽选替代。`AllModelsTest` 使用合成的 `DASHSCOPE_API_KEY`、`OPENAI_API_KEY`、MinIO/Rabbit/MySQL/Redis/JWT 占位变量，四个 model `enabled=false`、`ai.memory.enabled=false`、`ai.mcp.enabled=false`、Rabbit listener disabled；没有真实凭据或网络调用。

## 8. 清理与 Git 交付

正式 runner 的 finally 已精确删除 `r4ar4_formal_mysql` 容器与 `r4ar4_formal_data` 卷，停止全部任务 Java 进程。交付前的独立审计结果：

- 名称含 `r4ar4` 或 `review-m0` 的本任务遗留容器：0；本任务卷：0。
- 正式/预检列出的 10 个宿主端口 listener：0。
- command line 指向本 validation worktree 的遗留 Java/PowerShell 任务进程：0。
- 临时 `target`、原生日志、acceptance report 和 Surefire 输出在摘要写入本 handoff 后执行 Maven clean，不提交 Git。
- 全 reactor Maven clean 于 2026-09-01 08:38:17.6648963（Asia/Shanghai）成功结束；root/common/ai/file/document `target` 均不存在。
- 主 checkout 的最终 `git status --short` 无输出；milestone 最终仍为冻结 SHA。
- Git 提交仅包含验收 runner 与本 handoff；产品代码差异为 0。

在以上冻结范围内，A1-A7 与全部必列自动回归均完整通过，因此最终且唯一判定为 `A_PASSED`；按冻结阶段门，`B_AUTHORIZED`。
