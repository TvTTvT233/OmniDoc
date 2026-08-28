# Review M0 新一轮修复集成 FI-R1 交接

## 1. 最终判定

- 判定：`FIX_CANDIDATE_READY`。
- R4-A 输入：本分支最终唯一 `HEAD`；完整 SHA 由最终交付回执与 `git rev-parse HEAD` 给出。Git 提交无法在自身内容中稳定自引用自身 SHA。
- 自动状态：Review `AUTO_VERIFIED / MANUAL_PENDING`；本 FI 额外要求的真实 MySQL 8、原生双 Jar 启动与嵌入式真实 HTTP/JWT 专项均已运行通过。
- P-08：`LOCAL_ONLY`；原生 ai-service 运行时远程 MCP/模型成功调用均为 0。
- P-09：`NOT_RUN / PENDING`；未移动 milestone、未合并 main、未部署、未启动 R4-A/B/C/D、未标 `HUMAN_ACCEPTED`。

四个等价集成提交之后只新增本文这一份纯 handoff。本文提交前已完成 staged tree 的范围、敏感数据、JSON、PowerShell、空白与资源清理复核；提交后的最终 HEAD 复核结果由交付回执补充。

## 2. 固定输入与隔离

| 项目 | 实际值 |
| --- | --- |
| Git 根 | `C:\Users\lab\Desktop\docAI-main\OmniDoc` |
| 固定基线 | `fa7f1d8f8b3df984db84681bd15e55bd96c9a127` |
| 分支 | `integration/review-m0-r4-fixes-r1-01a04` |
| worktree | `C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-r4-fixes-r1-01a04` |
| F1 | `a6e9a02c42219cb032c1940567e47d32cbc5557d` |
| F2 | `57ef44ba49280be8cb38b9d6bc33cf0b25f651a1` |
| F3 | `9b139f8dcb167ba1f6cad056f60d75ad27ca857c` |
| F4-R1 | `16e33bb4fe9cd8b0472e5d574d110cc579dc8b71` |
| 禁止输入 | 旧 F4 `6ce350809da19aa71bdd5ba0072fdf1a893df71b`、旧 FI `e7fd6f16c4ebf01ccfd557c7bef404cfbd6eb8bc` |

本 worktree 从固定基线新建；共享 checkout、milestone、旧 FI、四个修复 worktree和 R4 worktree均未修改。开始时 `docker ps` 为空。旧 `r4http01a02-*` 容器、卷和其他任务资源未启动、复用、删除或修改。

已完整读取根 `AGENTS.md`、三份项目上下文、Review `README.md` 与 00..04 冻结文档、`M0-R3-FINAL-GATE.md`、四份新修复 handoff、旧 `M0-FIX-INTEGRATION.md`、R4-A `M0-R4-MYSQL8.md` 和 R4-B `M0-R4-FILE-MINIO-JWT.md`。任务明确禁止修改 `docs/PROGRESS.md`，因此本 FI 未更新该文件。

## 3. 提交、patch-id 与冲突

四个来源提交均已核对为 commit，并且恰好只有固定基线一个父提交。标准 stable patch-id 使用 `git show <commit> | git patch-id --stable`：

| 顺序 | 来源 | 集成提交 | stable patch-id | 冲突 | 单步 `diff --check` |
| ---: | --- | --- | --- | ---: | --- |
| 1 | `a6e9a02c42219cb032c1940567e47d32cbc5557d` | `b4c1ae5a249bb3090995c9a71d0267ff6926e4c8` | `49206f848dacaa1e395079cddf033ba4c46735a4` | 0 | exit 0 |
| 2 | `57ef44ba49280be8cb38b9d6bc33cf0b25f651a1` | `5b3847a735821ac2c109832696f1b2aebcafe7bb` | `3c140c8b186c8de1e9138d52b966b4c31fce3841` | 0 | exit 0 |
| 3 | `9b139f8dcb167ba1f6cad056f60d75ad27ca857c` | `a0a63f495d8c591c6e57c132d7419636d4dda7af` | `80edf56e7b78511efca581cf3b5a5dcdb8090fd2` | 0 | exit 0 |
| 4 | `16e33bb4fe9cd8b0472e5d574d110cc579dc8b71` | `e9764c1587142e2a5a11d5bb3b20986c792338f1` | `da322bec9ed8abb6e35d5220b27bf8cd470dca9f` | 0 | exit 0 |

四个提交两两路径交叠均为 0；没有机械冲突适配、测试组合适配或额外生产改写。基线到四提交 HEAD 恰好 48 个文件，与四个来源提交路径并集完全相等，无缺失或额外路径，整体 `git diff --check` 通过。旧 F4 和旧 FI 均不是当前 HEAD 的祖先。

## 4. F4-R1 行为与旧 F4 反例审计

- F4-R1 修改 17 个生产文件；来源 patch 与集成 patch 等价，且与 F1/F2/F3 无路径交叠。
- 零上下文生产 diff 的 `throw`、`return`、`if`、`switch`、`for`、`while`、`Result.fail` 增删均为 0。
- 生产差异只包含日志调用/参数、相关 import，以及两个 Application 对默认用户自动配置的显式排除。
- 旧 F4 独有的 `SecurityConfig`、`SensitiveLoggingConfig`、`FileMetadataController`、旧日志回归文件和旧 handoff 均未带入。
- 旧 F4 的异常类型/消息/cause、HTTP body/Map、Skill 返回、工具路由或文件行为改写均未出现在最终差异。
- `SensitiveLoggingR1Test`（ai/file）与 `OwnerAwareJwtCompatibilityR1Test` 通过；M0-01 恢复为冻结计数。

## 5. 必需真实专项门

### 5.1 真实 MySQL 8

实际执行 F1 自带 runner：

```powershell
$env:MAVEN_OPTS='-Xms16m -Xmx256m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss512k -Xshare:off'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8.ps1
```

结果：`2 / 2 / 0 / 0 / 0`，exit 0，无条件跳过。真实依赖为 `mysql:8.0`、utf8mb4、`utf8mb4_0900_ai_ci`、16 KiB InnoDB 页、MySQL Connector/J 与 Spring JDBC。

专项覆盖冷库、重复启动、原失败位置的 8/22 部分库、未知表/列形状 fail closed、V1/V2 到 V3、升级前数据保留、旧 `COMPLETED` 无快照 fail closed、全部实际索引动态枚举、无前缀索引、索引最坏长度不超过 3072 bytes、摘要键命中后的完整原值复核和碰撞 fail closed。

runner 只创建一个本任务 MySQL 容器，数据使用容器 tmpfs；没有创建命名网络或卷。`finally` 后本任务容器、网络、卷和运行容器均为 0。

### 5.2 最终源码 fat Jar 原生启动

先从四提交最终源码执行：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests package
```

4 个 reactor 模块 `BUILD SUCCESS`；直接使用生成的 `file-service-1.0.0.jar` 和 `ai-service-1.0.0.jar`，没有 target 临时覆盖类。

file-service 独立运行：

- JVM：`-Xms16m -Xmx160m`，独占端口 38941；Nacos 注册和 Rabbit listener 仅用启动参数关闭。
- `/v3/api-docs` HTTP 200，ready 1 次。
- owned API 缺失 JWT 为 401，无效 JWT 为 401；JWT-only 保持。
- stdout/stderr：10 类运行时精确 canary 0 命中，敏感字段模式 0，生成密码提示 0。
- 通过控制台中断触发进程退出；随后 PID 和 38941 listener 均为 0。

ai-service 独立运行：

- JVM：`-Xms16m -Xmx192m`，独占端口 38942；`ai.memory.enabled=false`、Review persistence disabled、Nacos 注册关闭、Rabbit listener 关闭、四个远程模型 disabled、`agent.mcp.enabled=false`、`fail-fast=true`。
- `/api-docs` HTTP 200，ready 1 次；受保护 Review 探测为 403，保持既有 ai-service matcher 语义。
- 日志明确出现一次 `MCP client is disabled; no remote clients were created`；远程 MCP 成功 0，远程模型成功 0。
- stdout/stderr：13 类运行时精确 canary 0 命中，敏感字段模式 0，生成密码提示 0。
- 通过控制台中断触发进程退出；随后 PID 和 38942 listener 均为 0。

两个 Jar 严格错峰，未同时运行；Jar 运行期间 Docker 容器为 0。

### 5.3 文件响应预提交完整性

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -am -DforkCount=1 `
  '-Dtest=FileApplicationTests,OwnerAwareFilePrecommitIntegrityHttpTest,OwnerAwareFileSecurityTest,OwnerAwareFileServiceTest,OwnedFileDownloadStagingTest,OwnerAwareJwtCompatibilityR1Test,SensitiveLoggingR1Test' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`28 / 28 / 0 / 0 / 0`，exit 0。其中嵌入式真实 Tomcat + JDK HttpClient + 生产 JWT/filter 的预提交完整性为 9/9，覆盖正常、数据库摘要不符、等长对象篡改、短读、长读、对象打开/读取异常、客户端中止、服务关闭/重启、stale 清扫和 payload 零残留。失败均非 200、无完整对象字节且完整性头不存在；staging ACL/lifecycle 专项通过。

## 6. Review 与修复回归

计数顺序均为 `discovered / passed / failures / errors / skipped`：

| 门 | 结果 | exit/status |
| --- | --- | --- |
| Review 默认资产 | `26 / 26 / 0 / 0 / 0` | exit 0；`ASSET_VERIFIED` |
| Review Integrated | `41 / 41 / 0 / 0 / 0` | exit 0；`AUTO_VERIFIED / MANUAL_PENDING` |
| Integrated adapter | `15 / 15 / 0 / 0 / 0` | 15 个 target PASS；15 个 P0 counter 全 0 |
| M0-01 | `100 / 87 / 0 / 0 / 13 expected` | exit 0 |
| M0-02 | `77 / 77 / 0 / 0 / 0` | exit 0 |
| M0-03 | `21 / 21 / 0 / 0 / 0` | exit 0 |
| M0-04 | `83 / 83 / 0 / 0 / 0` | exit 0 |
| M0-05 | `50 / 50 / 0 / 0 / 0` | exit 0 |
| M0-06 | `23 / 23 / 0 / 0 / 0` | exit 0 |
| Wave 2 | `6 / 6 / 0 / 0 / 0` | exit 0 |
| Wave 3 | `23 / 23 / 0 / 0 / 0` | exit 0 |
| Wave 3 recheck | `13 / 13 / 0 / 0 / 0` | exit 0 |
| R1-A | `8 / 8 / 0 / 0 / 0` | exit 0 |
| R1-B | `5 / 5 / 0 / 0 / 0` | exit 0 |
| R1-C ai | `14 / 14 / 0 / 0 / 0` | exit 0 |
| R1-C file | `11 / 11 / 0 / 0 / 0` | exit 0 |
| FIX1 精确 23 方法 | `23 / 23 / 0 / 0 / 0` | exit 0 |

Review Integrated 的 `readinessMissing=[]`，15 个冻结 P0 counter 全为 0。默认/Integrated 敏感扫描各扫描 1194 个 Git 可见文本文件，0 命中。

## 7. M0-01 的 13 个 expected skip

以下 skip 只属于冻结的 M0-01 历史/外部边界；其他本轮门均无 skip。真实 MySQL 方法仍作为 M0-01 历史外部门保留 Disabled，但本 FI 已通过第 5.1 节的独立真实 MySQL 门。

1. `realJpgOcrAndScannedDocumentAccuracyRemainUnverified`：需要原生 Tesseract、`eng/chi_sim` 和真实扫描件。
2. `realPngOcrAndConfidenceRemainUnverified`：需要原生 Tesseract、`eng/chi_sim` 和真实扫描件。
3. `ownedFileLookupRejectsAnonymousAndCrossUserAccess`：冻结 M0-04 pending baseline。
4. `ownedFileSnapshotProvidesConsistentOriginalNameBytesSizeAndSha256`：冻结 M0-04 pending baseline。
5. `ordinaryFileReadIsStreamingAndBounded`：冻结 M0-04 pending baseline。
6. `pdfExtractionRetainsFrozenSourceLocatorEvidence`：冻结 M0-03 parser pending baseline。
7. `docxExtractionRetainsOrderAndFrozenSourceLocatorEvidence`：冻结 M0-03 parser pending baseline。
8. `xlsxExtractionProvidesSheetAndCellRangeEvidence`：冻结 M0-03 parser pending baseline。
9. `imageExtractionHasReliableLocatorAndTypedOcrFailureSemantics`：冻结 M0-03 OCR pending baseline。
10. `parserFailuresMapToFrozenCannotConfirmOrSystemFailureSemantics`：冻结 M0-03 typed failure pending baseline。
11. `reviewM0SchemaUsesAnIsolatedVersionedJdbcMigration`：冻结 M0-02 migration pending baseline。
12. `reviewM0ApiUsesTheFrozenFourFieldErrorEnvelope`：冻结 M0-06 error-envelope pending baseline。
13. `realMysql8StartupAndMigrationRemainUnverified`：M0-01 外部边界占位；本 FI 的独立真实门已运行通过。

## 8. 旧能力与全量自动回归

| 能力/门 | 实际结果 | 边界 |
| --- | --- | --- |
| Agent / Conversation / Skill / File / Security 精确组合 | `72 / 72 / 0 / 0 / 0` | 包含 Agent execution/controller、Conversation controller/store、文件访问/删除、Review file adapter、安全与 F4-R1 日志/Skill 静态行为锁定 |
| file-service F3/F4 组合 | `28 / 28 / 0 / 0 / 0` | 嵌入式真实 HTTP/JWT；真实 MinIO 不在本 FI 必需门 |
| Document | `4 / 4 / 0 / 0 / 0` | mock 回归；真实 OCR/network `NOT_RUN` |
| `AllModelsTest` | `1 / 1 / 0 / 0 / 0` | context 通过；四模型 disabled，远程成功 0，canary/生成密码 0 |
| Memory V1 | 资产 `16/16` + Integrated `171/171` = `187/187` | 0 failure/error/skip；异步 `2/2`；Web production build 成功 |
| MCP Search | `80 / 80 / 0 / 0 / 0` | 真实 SDK mock server；真实 Tavily `NOT_RUN` |
| QV04 asset | `112 / 112 / 0 / 0 / 0` | exit 0；确定性 SHA-256 vector fixture；真实 Qdrant/远程 Embedding `NOT_RUN` |

Web `npm ci --no-audit --no-fund` exit 0；Vite production build exit 0，保留既有大 chunk 警告，未改 package/lockfile或升级依赖。

## 9. 主要实际命令

除第 5 节命令外，Review 统一入口与精确选择器为：

```powershell
.\scripts\review-m0\run-m0-acceptance.cmd -ReportDirectory ai-service\target\fi-r1-review-default
.\scripts\review-m0\run-m0-acceptance.cmd -Integrated -ReportDirectory ai-service\target\fi-r1-review-integrated
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.checklist.*Test,com.javaee.aiservice.review.m0.privacy.*Test,com.javaee.aiservice.review.m0.extraction.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.review.*Test,com.javaee.aiservice.review.m0.report.*Test,com.javaee.aiservice.review.m0.evidence.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.integration.wave2.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.integration.wave3.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.integration.wave3recheck.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=ReviewM0ApiIdempotencySnapshotTest,ReviewM0IdempotencySnapshotSchemaInitializerTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=ReviewM0CancellationRecoveryTest' test
```

FIX1 使用 `M0-R3-FINAL-GATE.md` 冻结的同一 23-method selector。旧能力入口：

```powershell
npm.cmd ci --no-audit --no-fund
.\scripts\memory\run-m07-acceptance.cmd -Integrated -ReportDirectory ai-service\target\fi-r1-memory
.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated
.\scripts\qdrant\run-qv04-acceptance.cmd -ReportDirectory ai-service\target\fi-r1-qdrant
.\mvnw.cmd -s .mvn\settings-local.xml -pl document-service -DforkCount=1 '-Dtest=DocumentServiceImplTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=AllModelsTest' '<four model enabled=false properties>' '-Dagent.mcp.enabled=false' test
```

## 10. 解析、安全、资源与清理

- tracked JSON：22/22 UTF-8/BOM-compatible parse，0 error。
- tracked PowerShell：22/22 使用 PowerShell Parser 解析，0 error。
- 本轮机器 JSON：10/10 parse，0 error。
- `git diff --check`：每个集成提交、四提交整体与 staged handoff 均 exit 0。
- 最终敏感扫描覆盖 Git 可见文本、Review report/evidence、ai Review M0/file 生产根和两份 Jar stdout/stderr；F4-R1 的 ai 日志改动另由 `SensitiveLoggingR1Test` 静态锁定。无真实密钥、运行 canary、JWT、对象定位、原文件名、预签名 URL 或生成密码提示命中。
- 本任务唯一 MySQL 容器已精确删除；任务容器/网络/卷残留 0，Docker 运行容器 0。
- 两个 Jar PID、端口 38941/38942 listener、临时启动配置和 staging payload 均为 0。
- Maven target、Review/Memory/MCP/QV04 report/evidence、Jar 日志、Web `node_modules`/`dist` 与其他本任务生成物在提交前按绝对工作树边界精确清理，不进入 Git。

## 11. 外部与人工边界

本 FI 已运行任务明确要求的真实 MySQL 8、原生 Jar HTTP-ready/JWT-only/LOCAL_ONLY/日志和嵌入式真实 HTTP/JWT 完整性门。以下仍按各自冻结边界记录，不由本 FI 外推：

- 真实 MinIO 外部对象服务与完整 ai-service -> file-service 双进程下载链：`NOT_RUN`；F3 必需门是嵌入式真实 HTTP/JWT，已通过。
- 真实 document/OCR 网络链与真实扫描件：`NOT_RUN`。
- 真实 Tavily、远程模型：`NOT_RUN / LOCAL_ONLY`；成功调用 0。
- 真实 Redis、RabbitMQ、Nacos：`NOT_RUN`；Jar 门仅使用 disabled/lazy 外部路径。
- 真实 Qdrant、远程 Embedding：`NOT_RUN`；QV04 仅 asset。
- 浏览器/UI/P-09、部署、生产容量/TLS/HA/备份/灾备、人工验收：`NOT_RUN / PENDING`。

因此本分支只授权严格串行 R4-A 使用最终唯一 HEAD，不授权跳过 R4-A/B/C/D，也不表示 `HUMAN_ACCEPTED`。
