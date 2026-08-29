# Review M0 A-FIX1/A-FIX2 集成与全量自动回归 R1

## 1. 结论与范围

- 最终判定：`FIX_INTEGRATION_FAILED`。
- 本任务只完成 A-FIX1、A-FIX2 的可追溯集成与自动回归；不是完整 R4-A，没有启动 B/C/D，也没有开发 UI/P08/P09。
- 完整重跑 A：`NOT_AUTHORIZED`。阻断原因见第 5 节；不得用两个修复分支各自的 PASS 代替集成结果。
- 集成分支：`integration/review-m0-a-fixes-r1`。
- 独立 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-a-fix-integration-r1`。
- 固定基线：`741a73bd2370a271c0386f3128a48077e55c95fc`（`integration/review-m0-r4-fixes-r1-01a04`）。
- 本文档提交无法在自身内容中稳定自指最终 SHA；准确结果 SHA 由交付消息和该分支的 `git rev-parse HEAD` 给出。
- 没有移动 `milestone/review-m0`，没有修改主 checkout，没有生成 diff/patch 文件，没有调用远程模型或远程 MCP。

## 2. 输入核对与集成顺序

集成前逐项确认固定基线、两个修复 worktree 和主 checkout 均清洁，并核对以下 ancestry：

1. `741a73bd2370a271c0386f3128a48077e55c95fc -> 6d9066ca9398487a1697d609d033dc0897beb7cf -> 1b9fed74f4952f222c766d0bcc0b6f77f6e1b136`
2. `741a73bd2370a271c0386f3128a48077e55c95fc -> eb57b799fbc8558f392bc0f6cdb67deef2b340c0 -> 9c6f5d82a8d830642e96e05fea4bf7cb276c5a32`

失败证据 `9f4bc5a449e4ca0ab3d773697ba6bdf23ef89b2a` 只读取了 handoff；它不是本分支 ancestor，也未进入代码基线。

实际 cherry-pick 顺序及集成后提交：

| 顺序 | 来源提交 | 内容 | 集成后提交 |
| ---: | --- | --- | --- |
| 1 | `6d9066ca9398487a1697d609d033dc0897beb7cf` | A-FIX1 实现 | `2f51158999992960109554288413a4b57fcad24f` |
| 2 | `1b9fed74f4952f222c766d0bcc0b6f77f6e1b136` | A-FIX1 handoff | `328c2db01889fe80fe23f9ac4196d7bce241e71e` |
| 3 | `eb57b799fbc8558f392bc0f6cdb67deef2b340c0` | A-FIX2 实现 | `abd9bb4762cbffffddb1956e558522d0e45bce67` |
| 4 | `9c6f5d82a8d830642e96e05fea4bf7cb276c5a32` | A-FIX2 handoff | `825612136a2e8bb26951ce57506ab7241606744e` |

四组 source/integrated patch-id 分别一致；全程无 cherry-pick 冲突，无集成修正提交。A-FIX1 与 A-FIX2 的生产文件集合不重叠。代码审计确认仍同时保留：DB 权威、`REQUIRES_NEW` 完整事务重试、有界跟随者重读、失败回滚无残留、异载荷 409；安全 reason code/requestId/category/count 且不记录 Throwable/message/body/SQL/堆栈；F4-R1 的异常传播、HTTP 状态和 API envelope 未被日志改动替换。

## 3. 组合专项结果

计数顺序均为 `discovered / passed / failures / errors / skipped`。

| 门 | 结果 | 判定 |
| --- | --- | --- |
| A-FIX1 精确组合 | `59 / 59 / 0 / 0 / 0` | PASS |
| A-FIX2 安全日志/API 合同 | `40 / 40 / 0 / 0 / 0` | PASS |
| A-FIX1 真实 MySQL 8 seed | `1 / 1 / 0 / 0 / 0` | PASS |
| A-FIX1 真实 MySQL 8 restart verify | `1 / 1 / 0 / 0 / 0` | PASS |
| M0-01 精确基线 | `100 / 87 / 0 / 0 / 13 expected` | PASS |
| Review 默认门 | `26 / 26 / 0 / 0 / 0`，`ASSET_VERIFIED` | PASS |
| Review Integrated | `41 / 41 / 0 / 0 / 0`，`AUTO_VERIFIED / MANUAL_PENDING` | PASS |
| acceptance adapter | `15 / 15` targets；15 个 P0 counter 全 0 | PASS |

真实 MySQL concurrent runner 使用 MySQL Community Server `8.0.46`，两个独立 datasource/事务管理器/store/wrapper 实例，覆盖 8/16/48 并发、瞬态竞争、已提交 `IN_PROGRESS` 跟随、胜出者回滚后的接管、异载荷 409、owner 隔离，以及应用对象和 MySQL 重启后的精确重放；所有批次业务写、完成记录和响应快照均各为 1，runner 没有条件跳过。

## 4. fat Jar 组合 canary 与日志

使用 `mvn -pl file-service,ai-service -am -DskipTests package` 构建原生 Spring Boot fat Jar，四模块 reactor 成功。运行只使用 LOCAL_ONLY、合成非真实凭据、禁用四个远程模型和 MCP；没有远程调用。

### 4.1 HTTP / schema / 并发

- file-service fat Jar：HTTP ready；成功运行日志中 generated security password 为 0。
- ai-service fat Jar：`/api-docs` 返回 HTTP 200、OpenAPI `3.0.1`；真实数据库 `review_m0_schema_version` 有 V001-V003 三行，最大版本 3。
- MySQL 版本：`8.0.46`；固定 canary 有 3 个 case、3 个 `COMPLETED` idempotency record、3 个不可变 response snapshot。

| 并发数 | HTTP 201 | HTTP 500/503 | 不同响应 body 数 |
| ---: | ---: | ---: | ---: |
| 8 | 8 | 0 | 1 |
| 16 | 16 | 0 | 1 |
| 48 | 48 | 0 | 1 |

- 48 路 key 使用不同载荷重放：HTTP 409。
- 停止应用、重启 MySQL、重新启动同一 Jar 后重放 48 路 key：HTTP 201，响应字节摘要与重启前一致；数据库仍为 3/3/3，没有重复业务写。
- Redis 缺失时 AIOps 与本地向量降级产生安全诊断，scheduler 未展开异常。
- 停止 MySQL 后的新写请求在客户端 90 秒门内没有返回既有 500，而是超时；服务日志仍记录 `GLOBAL_API_FAILED/INTERNAL_ERROR` 的安全诊断。该行为与 A-FIX2 handoff 的既有 500 canary 不一致，是第二项组合风险，不据日志安全 PASS 将其隐藏。

### 4.2 最终成功运行日志扫描

扫描 3 个成功 fat Jar 日志、合计 1329 行：

| 禁止项 | 计数 |
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
| 安全结构化 diagnostic | 409 |

diagnostic 分类为 Redis 406、System 3；事件包含 AIOps Redis 不可用、local vector Redis 降级和 DB 写失败进入公共 API handler。扫描器 `scripts/review-m0/run-a-fix2-runtime-log-scan.ps1 -RequireDiagnostic` 返回 PASS。

两次启动前置噪声不作为成功证据：第一次 file-service 未提供合成 MinIO 配置，启动前失败；第一次 `AllModelsTest` 未提供必需合成 API key，测试发现前失败。补齐非真实占位配置后，file-service ready、`AllModelsTest` 1/1 通过。失败日志已清理，不进入最终扫描。

## 5. 阻断失败：既有真实 MySQL 8 门

既有 FI-R1/R3 真实 MySQL 入口：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8.ps1
```

实际结果：`ReviewM0RealMysql8AcceptanceIT` 为 `2 discovered / 1 passed / 0 failure / 1 error / 0 skip`，exit 非 0。失败方法：

`longScopesConcurrentUniquenessCollisionChecksAndV3ChecksAreReal`

直接原因是并发直调 `JdbcReviewM0IdempotencyStore.claim` 时，`SELECT ... FOR UPDATE` 发生 MySQL deadlock 并以 `CannotAcquireLockException` 逃逸。A-FIX1 把瞬态重试所有权移动到完整 `REQUIRES_NEW` wrapper，并删除 store 内短重试；旧门仍直接并发调用 store，因而没有进入新的完整事务重试边界。

这是集成 HEAD 上真实存在的自动门失败。没有修改生产代码、测试或验收预期来规避它，也没有把它改记为 skip。依照阶段门，最终结论必须是 `FIX_INTEGRATION_FAILED`。

## 6. Review M0 全量自动回归

| 门 | 实际结果 |
| --- | --- |
| M0-02 model/store/job/idempotency | `77 / 77 / 0 / 0 / 0` |
| M0-03 checklist/privacy/extraction | `21 / 21 / 0 / 0 / 0` |
| M0-04 archive/file/material | `83 / 83 / 0 / 0 / 0` |
| M0-05 review/report/evidence | `50 / 50 / 0 / 0 / 0` |
| M0-06 API/config | `26 / 26 / 0 / 0 / 0` |
| Wave 2 | `6 / 6 / 0 / 0 / 0` |
| Wave 3 | `23 / 23 / 0 / 0 / 0` |
| Wave 3 recheck | `13 / 13 / 0 / 0 / 0` |
| FIX1 冻结 23-method selector | `23 / 23 / 0 / 0 / 0` |
| R1-C ai | `14 / 14 / 0 / 0 / 0` |
| file-service F3/F4 superset | `28 / 28 / 0 / 0 / 0` |
| DocumentServiceImplTest | `4 / 4 / 0 / 0 / 0` |

这些门覆盖 V001-V003、owner 隔离、CAS、取消恢复、lease/checkpoint、双 dispatcher、报告唯一发布/不可变/OUTDATED/回滚、普通文件和 ZIP 单叶、JWT 兼容与预提交完整性。除 M0-01 冻结的 13 个 expected skip 外，本表无 skip。

## 7. OmniDoc 旧能力回归

| 能力 | 实际结果 | 外部边界 |
| --- | --- | --- |
| Memory V1 | 资产 `16/16` + Integrated `171/171` = `187/187`；异步 `2/2`；Web production build 成功 | 无远程模型 |
| MCP Search | `80 / 80 / 0 / 0 / 0` | 只使用本地 mock server；无外部 MCP 调用 |
| Qdrant QV04 | `112 / 112 / 0 / 0 / 0` | 本地确定性 fixture；QV01-QV03 外部门仍按既有状态，未连接真实 Qdrant/Embedding |
| Agent/Conversation 相关组合 | M0-01 组合 `100/87/13 expected`，额外 `AllModelsTest 1/1` | 四模型 disabled，成功远程调用 0 |
| Document | `4 / 4 / 0 / 0 / 0` | mock 回归；真实 OCR/network 不在本任务 |

`npm ci --no-audit --no-fund` 成功；Vite build 仅保留既有大 chunk 警告。没有因日志配置修改预期或增加 skip。

## 8. 主要实际命令

```powershell
# A-FIX1 / A-FIX2
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=ReviewM0ApiIdempotencySnapshotTest,JdbcReviewM0IdempotencyStoreTest,ReviewM0JobSubmissionServiceTest,ReviewM0ApiContractTest,ReviewM0CancellationRecoveryTest,FormalReviewIdempotencyRegressionTest,Wave3RecheckIdempotencyAndIntegrityTest,ReviewReportAndDecisionTest,JdbcReviewReportStoreTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=AiOpsSchedulerTest,GlobalExceptionHandlerSafeLoggingTest,RuntimeSafeLoggingConfigurationTest,SafeDiagnosticLoggerTest,SensitiveLoggingR1Test,ReviewM0ApiContractTest,ConversationControllerTest,CurrentStructuredApiBaselineTest' test
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8-concurrent-idempotency.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8.ps1

# Review 默认/Integrated/adapter
.\scripts\review-m0\run-m0-acceptance.cmd -ReportDirectory ai-service\target\a-fix-integration-review-default
.\scripts\review-m0\run-m0-acceptance.cmd -Integrated -ReportDirectory ai-service\target\a-fix-integration-review-integrated

# M0 精确包选择器（逐条运行）
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.checklist.*Test,com.javaee.aiservice.review.m0.privacy.*Test,com.javaee.aiservice.review.m0.extraction.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.review.*Test,com.javaee.aiservice.review.m0.report.*Test,com.javaee.aiservice.review.m0.evidence.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.integration.wave2.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.integration.wave3.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.integration.wave3recheck.*Test' test

# 旧能力
npm.cmd ci --no-audit --no-fund
.\scripts\memory\run-m07-acceptance.cmd -Integrated -ReportDirectory ai-service\target\a-fix-integration-memory
.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated
.\scripts\qdrant\run-qv04-acceptance.cmd -ReportDirectory ai-service\target\a-fix-integration-qdrant
.\mvnw.cmd -s .mvn\settings-local.xml -pl document-service -DforkCount=1 '-Dtest=DocumentServiceImplTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -am -DforkCount=1 '-Dtest=FileApplicationTests,OwnerAwareFilePrecommitIntegrityHttpTest,OwnerAwareFileSecurityTest,OwnerAwareFileServiceTest,OwnedFileDownloadStagingTest,OwnerAwareJwtCompatibilityR1Test,SensitiveLoggingR1Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests package
```

FIX1 的 23-method selector 原样取自 `M0-R3-FINAL-GATE.md`；`AllModelsTest` 使用合成非真实必需环境变量、四模型 disabled、Memory/MCP/Nacos/Rabbit listener disabled。fat Jar canary 使用原生 JDK/.NET HTTP 客户端和生产 JWT filter；认证值、请求/响应 body、数据库口令均未写入交付文档或最终日志。

## 9. 清理、残余风险与下一步

- 本任务专用 MySQL 容器 `review_m0_a_int_r1_mysql` 已精确删除；本任务网络/卷均未创建，残留 0。
- 本任务启动的 Java 进程为 0；端口 39151、39152、43361 listener 为 0。
- 临时 fat Jar 日志、失败启动日志、Maven target、acceptance report、Web `node_modules/dist` 等忽略产物在交付前清理；不触碰其他任务已有容器、卷、网络、进程或 worktree。
- 残余阻断一：既有真实 MySQL 门在 direct-store 并发 deadlock 上 error；必须由后续明确授权的修复任务决定是让门经由完整事务 wrapper 验证，还是为 store 建立与新所有权一致的明确契约。当前任务不修改验收或产品。
- 残余阻断二：MySQL 完全不可用时 fat Jar 新写请求 90 秒内不返回原有 500；虽日志安全，HTTP 时限/状态需要单独诊断。
- 下一步：`完整重跑 A = NOT_AUTHORIZED`。只有上述阻断修复后，在新的独立任务和最终集成 HEAD 上重新通过全部门，才可重新评估。
