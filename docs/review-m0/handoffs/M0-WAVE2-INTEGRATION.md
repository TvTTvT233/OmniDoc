# Review M0 Wave 2 独立集成与阶段门交接

## 1. 结论

- 日期：`2026-08-15`
- 状态：`READY_FOR_M0_05`
- 共同起始基线：`c96303497c31c40311d406fc2f1d680cc8db56be`
- 集成分支：`review/m0-wave2-integration-01a00579`
- 独立 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-wave2-integration-01a00579`
- M0-03 来源提交：`fd69beef46fa36f29d46b50ef5989852f3a36afb`
- M0-03 集成提交：`cabb5764ff5f3029556a674978e77abf573a9f0f`
- M0-04 来源提交：`c8c33cf1ba61a6237de0388b176a3a9ed4e22376`
- M0-04 集成提交：`4661aa6ef234191674b41d2c2c3dd1c3b1cfa405`
- 协调提交：本交接、M0-01 机械演进和 Wave 2 组合测试所在的唯一后续提交；提交不能稳定记录自身 SHA，完整 SHA 由最终交付报告给出。

M0-03、M0-04 及二者与 M0-02 的本地组合阶段门全部通过，没有发现必须修改 M0-02/M0-03/M0-04 生产代码才能关闭的缺陷。协调者没有修改任何生产代码，也没有开发 M0-05；本交接所在最终提交可作为 M0-05 的起始 SHA。

`READY_FOR_M0_05` 只表示 Wave 2 冻结范围和本地组合门通过。真实 file-service owner 证明、MinIO、MySQL 8、远程模型、浏览器和最终生产适配器仍为 `NOT_RUN`；fail-closed 不能解释为真实链路已经可用。最终 Integrated 验收仍按冻结门禁返回 `PENDING_INTEGRATION`，这与 M0-05 及后续 M0-06/M0-07、外部接入尚未完成相符。

## 2. 提交拓扑与来源追溯

两份来源都只有一个父提交，而且父提交都精确等于共同基线：

```text
fd69beef46fa36f29d46b50ef5989852f3a36afb
  <- c96303497c31c40311d406fc2f1d680cc8db56be

c8c33cf1ba61a6237de0388b176a3a9ed4e22376
  <- c96303497c31c40311d406fc2f1d680cc8db56be
```

协调分支严格按 M0-03 后 M0-04 顺序集成：

```text
<唯一协调提交>
  <- 4661aa6ef234191674b41d2c2c3dd1c3b1cfa405  M0-04
  <- cabb5764ff5f3029556a674978e77abf573a9f0f  M0-03
  <- c96303497c31c40311d406fc2f1d680cc8db56be  共同基线
```

使用父子 `git diff | git patch-id --stable` 核对：

| 来源 | 来源 patch-id | 集成 patch-id | 结论 |
| --- | --- | --- | --- |
| M0-03 | `ba388bd2f964898991692a9c43f7c36ddb520594` | `ba388bd2f964898991692a9c43f7c36ddb520594` | 精确一致 |
| M0-04 | `e3af7a10a761a8da9a5b7272d043fe90a997e081` | `e3af7a10a761a8da9a5b7272d043fe90a997e081` | 精确一致 |

集成在新建的唯一 worktree/分支中从共同基线开始，没有修改调用方当前 checkout 或其他 worktree，也没有发生需要产品选择的 cherry-pick 冲突。未替用户决定 `P-01` 至 `P-13`。

## 3. 来源与授权范围审计

### 3.1 M0-03

M0-03 来源提交新增 36 个文件，全部属于：

- `ai-service/src/main/java/com/javaee/aiservice/review/m0/checklist/**`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/privacy/**`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/extraction/**`
- 上述三类对应测试
- `docs/review-m0/handoffs/M0-03.md`

### 3.2 M0-04

M0-04 来源提交新增 37 个文件，全部属于：

- `ai-service/src/main/java/com/javaee/aiservice/review/m0/material/**`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/file/**`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/archive/**`
- 上述三类对应测试
- `docs/review-m0/handoffs/M0-04.md`

### 3.3 排除项

两份来源均未修改 M0-02 所有权文件、公共 POM/YAML、Agent、前端、冻结契约、M0-07/acceptance 资产、Memory/MCP/Qdrant、`scripts/review-m0/**` 或 `docs/PROGRESS.md`。新增 Review M0 生产包对 Agent execution、Conversation/Memory、MCP、Qdrant、H2、Mockito、test fake/stub 的禁止引用扫描为 0；`permitAll`、原始 `X-User-Id`、bucket/MinIO 直连、生产日志器、`System.out` 和 `printStackTrace` 扫描为 0。

协调提交只修改第 4 节和第 7 节列出的授权测试资产与本交接；生产代码修改为 0，来源 handoff 修改为 0。

## 4. M0-01 历史基线的机械演进

M0-04 已报告旧 M0-01 中两个“生产代码不存在 ZIP 能力”的负断言在安全 ZIP 能力实现后失败。本次没有删除、Disabled 或空化断言，而是做以下最小演进：

- `CurrentFileServiceBaselineTest`：改为验证 Review M0 digest-bound 单 ZIP 条目流式读取，同时继续验证真实 file-service owner 适配器 fail-closed，且不存在 header/bucket/MinIO 绕过。
- `CurrentDocumentParserBaselineTest`：改为验证 `ZipInputStream`、条目/总量/压缩比/时间限制、NFC 重复与路径安全，并确认旧 document parser 与 Review M0 archive 边界仍分离。
- `PendingImplementationContractTest`：原 4 个 ZIP `Disabled` 目标改为正向生产证据和 M0-04 精确测试证据；真实 owner、外部文件读取、解析器/OCR、M0-06、真实 MySQL 等边界继续 Disabled。
- `M001CapabilityMatrixTest`：新增防回退断言，保证只把内部 ZIP 能力升级，外部文件边界不被错误升级。
- `m0-01-capability-matrix.json`：仅 `ZIP_SAFE_SINGLE_ENTRY_READ` 从 `CURRENT_GAP/PENDING_IMPLEMENTATION` 改为 `CURRENT_VERIFIED`；`FILE_OWNER_AUTHORIZATION`、`FILE_ARTIFACT_SNAPSHOT_SHA256`、`FILE_STREAMING_READ` 继续为 `CURRENT_GAP`。

历史 `M0-01.md` 未修改。原冻结基线为 99 discovered / 82 passed / 17 skipped；在 M0-04 能力加入但测试未演进时，两个旧负断言如实失败。机械演进后结果为 100 discovered / 87 passed / 0 failures / 0 errors / 13 skipped：4 个已实现 ZIP 目标从 Disabled 改为正向证据，新增 1 个能力矩阵防回退测试，其余真实外部边界保持未验证。

## 5. M0-03 组合审计

生产实现、M0-03 精确测试、M0-02 FIX1 回归及 Wave 2 组合测试共同证明：

- 抽取结果只能形成 `CANDIDATE_DRAFT`；缺可靠定位、未逐项确认、`NEEDS_USER_DECISION`、缺 `caseSensitive` 或非法/不安全正则时不能发布正式清单。
- 正式清单快照不可变；依据角色/修订变化会使旧快照成为 `STALE_BASIS`，旧报告成为 `OUTDATED`。
- `LOCAL_ONLY` 不构造也不调用远程客户端。
- RemoteConsent 在事务写边界精确绑定 owner、case、operation、current revision、preview/digest、逻辑 job 和未消费状态；并发消费恰好一个成功并产生唯一匹配 remote call。
- `SEND_INTENT` 未知结果不会自动重发；远程失败只终结 Job 为 `FAILED`，不会发布正式清单或报告；远程响应只生成候选。
- 敏感正文、脱敏预览内容和远程载荷不进入日志、异常、Job/checkpoint/remote-call 机器记录或报告。

## 6. M0-04 组合审计

生产实现、M0-04 精确测试、M0-02 FIX1 回归及 Wave 2 组合测试共同证明：

- 现有 file-service 不能证明 fileId owner 时，默认生产字节源返回 `SERVICE_UNAVAILABLE` 并 fail-closed；不存在 `permitAll`、原始 `X-User-Id`、bucket 或 MinIO 直连绕过。
- PDF/DOCX/XLSX/JPG/PNG 是材料；ZIP 只作为 source 容器，展开后的五类叶子才形成材料，ZIP 自身不能成为材料。
- ZIP 使用流式读取并限制条目数、条目/总解压量、压缩比、名称长度、目录深度和处理时间；拒绝路径穿越、绝对/盘符/UNC 路径、NUL/控制字符、重复 NFC 路径、嵌套 ZIP、加密、CRC/损坏、unsupported leaf 和 zip bomb。任一危险会回滚整个附加事务，不发布部分 source/material。
- `submissionFileName` 改名只改变 Review 数据；原字节、`originalFileName`、`objectKey` 和快照元数据保持不变。
- 普通下载只打开目标文件；ZIP 下载只流式打开 digest-bound 目标条目。`Content-Disposition` 去除 CR/LF/引号/分隔符注入，提供 ASCII fallback 与 RFC 5987 `filename*`。
- 材料、分配、名称修订与旧报告 `OUTDATED`/Case 当前报告指针清除位于同一事务；材料修订使旧上传确认失效。
- owner 隔离贯穿附加、快照读取、确认、分配、改名、普通下载和 ZIP 条目下载。

## 7. Wave 2 协调者专用组合测试

新增文件：

- `ai-service/src/test/java/com/javaee/aiservice/review/m0/integration/wave2/Wave2CrossModuleIntegrationTest.java`

6 个真实 Spring JDBC/H2 MySQL-mode 组合测试覆盖：

1. 依据、正式清单和材料变化分别推进正确 revision，旧报告 `OUTDATED`，互不覆盖其他 revision。
2. 同一 owner/case 的 M0-02 Store 一致读取正式清单与材料快照；跨 owner 的 Case、清单和材料路径均表现为不存在。
3. 已发布清单和已排队 Job 不会把未确认上传误判为可审查，也不会产生报告/finding/current report 指针。
4. PDF 正文、ZIP entry 正文和脱敏预览标记不进入其他模块日志、异常、Job、checkpoint、remote-call、diagnostic 或报告；预览正文只保留在其明确业务记录中。
5. `LOCAL_ONLY` 材料处理与清单抽取不构造/调用远程客户端，不产生 consent/call。
6. 清单发布、材料附加和 Job 提交真实三线程并发时，业务 CAS 只允许成功或版本冲突；Case revision/version 与已提交行一致，source/material 原子，Job/幂等记录完整，不产生半报告或丢失更新。

结果：6 discovered / 6 passed / 0 failures / 0 errors / 0 skipped，退出 0。

## 8. 实际复跑命令与计数

测试使用单 fork 和受限 JVM 参数；以下为决定选择范围的命令，均从本独立 worktree 根目录执行。

```powershell
$env:MAVEN_OPTS='-Xms16m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-DargLine=-Xms32m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off' '-Dtest=<精确选择器>' test
```

| 套件 | 精确 `-Dtest` 选择器 | discovered / passed / failed / errors / skipped |
| --- | --- | --- |
| M0-03 | `com.javaee.aiservice.review.m0.checklist.*Test,com.javaee.aiservice.review.m0.privacy.*Test,com.javaee.aiservice.review.m0.extraction.*Test` | `21 / 21 / 0 / 0 / 0` |
| M0-04 | `com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test` | `76 / 76 / 0 / 0 / 0` |
| M0-02 | `com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test` | `64 / 64 / 0 / 0 / 0` |
| FIX1 五项定向回归 | 23 个冻结方法：B-01 2、B-02 3、B-03 7、B-04 3、B-05 8 discovered | `23 / 23 / 0 / 0 / 0` |
| M0-01 演进基线 | `com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest` | `100 / 87 / 0 / 0 / 13` |
| Wave 2 组合 | `com.javaee.aiservice.review.m0.integration.wave2.*Test` | `6 / 6 / 0 / 0 / 0` |

FIX1 的完整定向选择器命令为：

```powershell
$fix1Selector = 'ReviewM0JobSubmissionServiceTest#crossOwnerCaseSubmissionIsNotFoundAndLeavesNoJobOrIdempotencySideEffect+concurrentCrossOwnerSubmissionsAllFailWithoutCreatingProbeableState,' +
  'JdbcReviewReportStoreTest#crossCaseAndOwnerPublicationRollsBackReportJobPointerAndIdempotencyState+reportInputFingerprintMustEqualTheLeasedJobAndFailureHasNoPublicationSideEffect+succeededJobCannotBeIdempotentlyReusedToProbeAnotherCaseReport,' +
  'JdbcReviewM0RemoteCallStoreTest#confirmedPayloadDigestMustMatchActualSendBytes+crossOwnerAndCaseConsentIsHiddenAndLeavesConsentAndCallStateUntouched+mismatchedConsentOwnerOnTheSameCaseCannotAuthorizeSendIntent+oldBasisRevisionAndInvalidatedPreviewCannotAuthorizeSendIntent+operationMismatchCannotAuthorizeSendIntentEvenWithTheSameDigest+consumedConsentWithoutItsMatchingRemoteCallCannotBeConsumedAgain+concurrentConsentConsumptionAllowsExactlyOneLogicalJob,' +
  'ReviewM0ModelContractTest#zipRemainsAFormalInputContainerButCannotBecomeAMaterialUnit,' +
  'ReviewM0DdlConstraintTest#ordinarySourceHasOneTopLevelMaterialWhileZipAllowsDistinctLeavesOnly+zipSourceIsSupportedButDatabaseRejectsTheContainerAsAMaterialUnit,' +
  'ReviewM0SchemaInitializerTest#initializesAllFrozenTablesAndRepeatedInitializationIsIdempotent+resumesVersionTwoAfterAInterruptedCyclicReferenceMigration+strictValidationRejectsEveryMissingV002ForeignKey+strictValidationUsesForeignKeyStructureRatherThanOnlyConstraintName+strictValidationRejectsCascadingRulesOnV002ForeignKeys'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-DargLine=-Xms32m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off' "-Dtest=$fix1Selector" test
```

M0-01 前置 classpath 命令也成功退出 0：

```powershell
.\mvnw.cmd -q -pl document-service -am dependency:build-classpath '-Dmdep.outputFile=target/m0-pb-runtime-classpath.txt' '-Dmdep.includeScope=runtime'
```

模块回归：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl document-service -DforkCount=1 '-Dtest=DocumentServiceImplTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -DskipTests compile
```

- `DocumentServiceImplTest`：4 / 4 / 0 / 0 / 0，退出 0；这是 mock 单元测试，不是真实 document-service/OCR 网络验证。
- `file-service compile`：`BUILD SUCCESS`，退出 0；不是 owner/MinIO 运行验证。

`AllModelsTest` 使用 `synthetic-not-real` 非秘密占位值、四个精确 indexed 模型 `enabled=false` 和 RabbitMQ listener 禁用参数运行：

```powershell
$env:DASHSCOPE_API_KEY='synthetic-not-real'
$env:OPENAI_API_KEY='synthetic-not-real'
$env:REDIS_PASSWORD='synthetic-not-real'
$env:RABBITMQ_USERNAME='synthetic-not-real'
$env:RABBITMQ_PASSWORD='synthetic-not-real'
$env:MINIO_ACCESS_KEY='synthetic-not-real'
$env:MINIO_SECRET_KEY='synthetic-not-real'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-DargLine=-Xms32m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off' '-Dtest=AllModelsTest' '-Dai.models.openai[qwen3.7-max-2026-05-20].enabled=false' '-Dai.models.openai[glm-5].enabled=false' '-Dai.models.openai[kimi-k2.5].enabled=false' '-Dai.models.openai[MiniMax-M2.5].enabled=false' '-Dspring.rabbitmq.listener.simple.auto-startup=false' test
```

结果：1 / 1 / 0 / 0 / 0，退出 0；日志确认真实模型成功数 `0/4`。Redis 连接告警、MinIO client 的本地构造不构成真实外部依赖验证。

## 9. 默认与 Integrated 验收

```powershell
cmd.exe /d /v:on /c "call .\scripts\review-m0\run-m0-acceptance.cmd & set gateExit=!ERRORLEVEL! & echo GATE_EXIT=!gateExit! & exit /b !gateExit!"
cmd.exe /d /v:on /c "call .\scripts\review-m0\run-m0-acceptance.cmd -Integrated & set gateExit=!ERRORLEVEL! & echo GATE_EXIT=!gateExit! & exit /b !gateExit!"
```

- 默认：26 discovered / 26 passed / 0 failures / 0 errors / 0 skipped；最终协调提交上的敏感信息门扫描 1088 个文本文件无命中；`ASSET_VERIFIED`；`GATE_EXIT=0`。
- Integrated：资产自检同样 26/26，敏感信息门无命中；报告为 `PENDING_INTEGRATION / NOT_READY`、`finalAcceptance=false`；原生批处理内部明确捕获 `GATE_EXIT=2`。外层执行宿主可能把任意非零显示为通用失败码，但脚本内捕获值和冻结协议均为 2。
- Integrated `readinessMissing` 仍为 production adapter、精确 integrated selection、静态扫描 roots 未声明；P0 生产计数仍为 `null`，没有把本地阶段门冒充最终生产验收。

## 10. 协调提交修改文件

除本交接外，协调者只改动以下授权测试资产：

```text
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/CurrentDocumentParserBaselineTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/CurrentFileServiceBaselineTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/M001CapabilityMatrixTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/PendingImplementationContractTest.java
ai-service/src/test/resources/review-m0/baseline/m0-01-capability-matrix.json
ai-service/src/test/java/com/javaee/aiservice/review/m0/integration/wave2/Wave2CrossModuleIntegrationTest.java
docs/review-m0/handoffs/M0-WAVE2-INTEGRATION.md
```

没有改动 fixture/schema 资源，因为现有精确资产足以证明组合边界。没有删除测试、增加 Disabled、放宽为空断言或修改生产实现来迁就门禁。

## 11. 静态门禁与工作树

- `git diff --check c96303497c31c40311d406fc2f1d680cc8db56be..HEAD`：通过。
- 协调提交授权路径外文件：0；生产代码变化：0。
- 禁止依赖与安全绕过扫描：0 命中。
- 默认与 Integrated 敏感信息扫描：均无 secret 或 fixture PII 命中。
- 构建产物只写入被忽略的 `target/**`；最终提交后工作树要求洁净。

## 12. 外部依赖与未验证项

| 依赖/链路 | 状态 | 本次可声明边界 |
| --- | --- | --- |
| H2 MySQL mode | `RUN` | 真实进程内 JDBC/事务/CAS/并发负例；不是 MySQL 8 |
| MySQL 8 | `NOT_RUN` | 真实 metadata、锁、死锁与多连接语义未验证 |
| 真实 file-service owner 证明 | `NOT_RUN` | 当前默认适配器按要求 fail-closed，不能声称 fileId 可用 |
| MinIO/对象存储 | `NOT_RUN` | compile/client 构造不是对象存储链路验证 |
| 真实 document-service/OCR | `NOT_RUN` | 只运行既有 mock/本地 parser 基线 |
| 真实远程模型/远程发送 | `NOT_RUN` | 四模型关闭，真实成功数 0/4，没有发送载荷 |
| 浏览器/双用户 JWT HTTP | `NOT_RUN` | M0-06/最终集成范围，当前无端到端 Controller 装配验证 |
| Redis/RabbitMQ/多节点 | `NOT_RUN` | 本地数据库 Job、禁用 listener 和连接告警不构成验证 |

仍待 M0-05 实现确定性报告与证据；其后仍有 M0-06 API/装配和 M0-07 最终 Integrated adapter/P0/P1 生产组合门。M0-05 必须从本交接所在最终协调提交的完整 SHA 开始。本任务到此停止，不开发 M0-05。
