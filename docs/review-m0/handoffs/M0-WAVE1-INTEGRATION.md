# Review M0 Wave 1 独立集成与阶段门交接

## 1. 结论

- 日期：`2026-08-14`
- 状态：`WAVE1_GATE_FAILED / NOT_READY / M0_03_M0_04_BLOCKED`
- 固定起始基线：`d9b5f2d2ae18d46fbc1d7483ee86662ee40b8f90`
- M0-02 来源提交：`b1c1f59c585fdbfab7e834b18ca88a3544d028e8`
- 独立集成分支：`review/m0-wave1-gate-019ff65c`
- 独立 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-wave1-gate-019ff65c`
- 集成方式：`git merge --ff-only b1c1f59c585fdbfab7e834b18ca88a3544d028e8`
- 实现/测试/迁移/POM/YAML/冻结契约/门禁/前端/`docs/PROGRESS.md` 修改：无
- 本交接以外新增文件：无

M0-02 的精确测试、M0-01 基线、模块回归、应用上下文和资产门禁均达到数值要求；但是独立源码审计和进程内 JDBC 复现确认了跨案例 owner 绑定、跨案例结果发布、ZIP 容器材料化和 strict schema validate 四类生产缺陷。因此 Wave 1 阶段门失败，不得标记 `READY`，本提交不得作为 M0-03/M0-04 的共同开发起点。

## 2. 拓扑、来源等价与文件所有权

来源拓扑经只读核对为：

```text
b1c1f59c585fdbfab7e834b18ca88a3544d028e8
  <- d9b5f2d2ae18d46fbc1d7483ee86662ee40b8f90
```

- 来源提交的唯一父节点精确等于指定基线；
- `merge-base` 精确等于指定基线；
- 指定基线是来源祖先，提交跨度为 1；
- 协调分支从指定基线创建后执行 fast-forward，集成后 HEAD 原样保留来源 SHA；
- 来源提交与基线到来源 diff 的 stable patch-id 都是 `08b25d048b5fba60bbe7413a37178e738615ea15`；
- 来源相对基线恰好新增 108 个文件、5533 行，没有删除或改写基线文件；
- 108/108 全部位于 M0-02 冻结所有权路径，授权外文件为 0；
- 根/`ai-service` POM、公共 YAML、`AgentExecutionService.java`、`AgentToolRegistry.java`、`scripts/review-m0/**`、acceptance 资产、Web、`docs/PROGRESS.md` 相对基线 diff 均为 0；
- Review 生产包对 `AgentExecutionService`、`AgentToolRegistry`、Memory/MCP/Qdrant 生产包的禁止引用为 0；
- 原 checkout 和其他 worktree 未被本任务修改。

## 3. 已通过的实现审计

以下结论只描述本地源码、真实 JDBC/H2 MySQL-mode 和确定性测试，不代表真实 MySQL 或外部服务已验证。

### 3.1 领域与指纹

- 冻结公共枚举名称、Jackson 字符串往返、正式扩展名白名单和错误码与冻结文档一致；
- `LogicalFileName` 执行 NFC、叶子名、控制字符和路径分隔拒绝，扩展名比较不区分大小写；
- `ReviewInputFingerprint` 包含契约版本、案例、依据、正式快照、材料、分配、逻辑名、上传确认和引擎版本，并按 Unicode code point 稳定排序；独立 JVM 摘要一致测试通过；
- Job、清单和报告新鲜度的主要非法倒退被拒绝，`OUTDATED -> CURRENT` 被拒绝；
- 规范 JSON/digest 在清单快照、Job 请求、checkpoint 和 evidence 的相应 Store 写入/读取边界校验。

### 3.2 DDL、事务、幂等与 Job 基础能力

- V001 创建 1 张 `review_m0_schema_version` 和冻结的 21 张 Review 业务表，V002 添加 4 个循环外键；schema version 为 2；
- H2 MySQL-mode 已执行空库初始化、重复初始化和 V002 部分迁移恢复；真实 MySQL 8 未执行；
- `TransactionTemplate` 使用 Spring 默认 `PROPAGATION_REQUIRED`；外层事务回滚能够回滚修订推进和报告过期；
- Case 修订 CAS、同事务 `OUTDATED`/指针清空、同输入报告唯一、幂等同键同载荷重放/异载荷冲突、并发 claim 单创建者均有非空断言；
- `ReviewM0JobHandler`/`ReviewM0JobHandlerRegistry` 是独立 SPI；租约竞争、过期恢复、checkpoint 重放、取消、有限重试和结果发布回滚/重放均有非空断言；
- `CANCEL_REQUESTED` 不被 `acquireNext` 领取且不能保存新 checkpoint，`CANCELLED` 无结果；
- Store 为 Spring JDBC 实现，没有用内存 Map/fake 替代数据库，也没有 H2 专用生产分支；
- handoff 对 H2 与真实 MySQL 边界陈述真实，没有把 H2 写成真实 MySQL 已验证。

## 4. 阶段门阻塞缺陷

### B-01：Job 提交没有把认证 owner 与 Case owner 绑定（P0）

`ReviewM0JobSubmissionService.submit(...)` 只比较 `IdempotencyScope.ownerId` 与 `queuedJob.userId`，随后直接调用 `ReviewM0JobStore.create(...)`。`review_m0_job.case_id` 只有普通 Case FK，`user_id` 没有与 `review_m0_case.user_id` 建立一致性约束；`create` 也不执行 `case_id + user_id` owner 谓词。

独立 JShell 使用交付的真实 JDBC Store、真实 Review DDL 和进程内 H2 MySQL-mode 复现：先创建 `victim` 拥有的 `victim-case`，再以 `attacker` scope 提交 `userId=attacker/caseId=victim-case` 的 Job，调用成功并输出：

```text
CROSS_OWNER_SUBMIT=attacker/victim-case/QUEUED
```

这违反“所有案例资源按认证用户隔离、异主表现为不存在”和“owner 条件贯穿 Store”。现有 `ReviewM0JobSubmissionServiceTest` 只覆盖 owner 与 Job user 相同的正例及不存在 Case 的 FK 回滚，没有覆盖 Case 属于另一用户的负例。

### B-02：Report 发布没有校验 Job/Case/owner/输入关联（P0）

`JdbcReviewReportStore.publishOwned(...)` 用 `report.jobId + workerId` 锁定 Job，再独立用 `ownerId + report.caseId` 锁定 Case，但没有验证已租赁 Job 的 `caseId/userId/jobType/inputFingerprint` 与报告、owner 和当前输入一致，也没有验证报告的 `uploadConfirmationId` 属于该 Case 当前快照/材料修订。

独立 JDBC 复现创建两个 owner 不同的 Case，用 `case-other` 的已租赁 Job 调用 `publishOwned(owner-victim, ..., report.caseId=case-victim)`；事务成功，Job 与报告跨案例绑定：

```text
CROSS_CASE_PUBLICATION_JOB=case-other;REPORT=case-victim
```

这违反 owner 隔离、结果发布关联和“发布报告 + Job SUCCEEDED”原子事务的语义完整性。现有报告测试只使用同案例 Job/Report 正例，没有跨案例/跨 owner/错误 input fingerprint/错误 upload confirmation 负例。

### B-03：RemoteConsent Store 未校验 case、owner、operation 和 revision（P0）

`JdbcReviewM0RemoteCallStore.beginSendIntent(...)` 分别验证 Job 是持租约的 `CHECKLIST_EXTRACTION`、Consent 的 `consent_id` 和 `payload_digest`，但读取的 `ConsentRow` 只有 `consumedByJobId/payloadDigest`。它没有验证 Consent 与 Job 的 `case_id/user_id` 相同，也没有验证冻结的 `operation`、`basis_revision`、Preview 当前状态或当前 Case basis revision。

因此仅凭已知 `consentId + 相同 digest`，一个案例的 Job 可以消费另一案例的授权。现有测试只覆盖同案例正例、digest 不同和 outcome unknown，没有跨案例或 revision/operation 不匹配负例。M0-03 在此基础上不能安全依赖 `beginSendIntent(...)`。

### B-04：ZIP 容器可被领域模型和 DDL 接受为材料单元（P0）

冻结契约要求 ZIP 只作容器，容器本身不是材料单元。当前 `SubmissionMaterial` 只在 `kind=ZIP_ENTRY` 时拒绝 `FormalFileType.ZIP`，因此接受 `TOP_LEVEL_FILE + ZIP`；`ck_review_m0_material_type` 也把 `ZIP` 列入材料类型允许集合。

独立领域探针实际构造成功：

```text
ZIP_CONTAINER_ACCEPTED_AS_MATERIAL=TOP_LEVEL_FILE/ZIP
```

现有 DDL 测试验证了普通源最多一个顶层材料和 ZIP 叶子路径唯一，但没有断言 ZIP 容器不能形成 `review_m0_material`。这违反容器-only 不变量，并会给 M0-04 留出错误持久化状态。

### B-05：strict validate 会漏过已安装版本中的外键漂移

`ReviewM0SchemaInitializer.validate()` 只验证版本行、通过空查询验证列名，并收集一组命名索引/唯一约束；它没有验证 V002 的 4 个循环外键，也没有严格验证全部 FK/check、列类型/可空性和索引列组成。

独立 JDBC 探针在 schema version 仍为 2 时删除 `fk_review_m0_remote_consent_job`，随后 `validate()` 无异常返回：

```text
STRICT_VALIDATE_MISSED_DROPPED_FK=true
```

因此“部分迁移恢复”正例不能替代启动 strict validate 的缺失约束负例。真实 MySQL 未运行时，此缺口尤其不能由 H2 正例推断为安全。

## 5. 测试与门禁实际结果

以下命令均在独立 worktree 的来源原样 HEAD 上执行；最终 handoff 只增加本文档，不改变 Java、SQL、测试或门禁行为。

### 5.1 M0-02 精确集合

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service '-Dtest=com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test' test
```

- 45 discovered / 45 passed / 0 failures / 0 errors / 0 skipped；
- `BUILD SUCCESS`，退出 0；
- 上述 B-01 至 B-05 负例不在这 45 项中，因此数值门通过但生产阶段门失败。

### 5.2 M0-01 精确基线

```powershell
.\mvnw.cmd -q -pl document-service -am dependency:build-classpath '-Dmdep.outputFile=target/m0-pb-runtime-classpath.txt' '-Dmdep.includeScope=runtime'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service '-Dtest=com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest' test
```

- classpath 准备退出 0；
- 99 discovered / 82 passed / 0 failures / 0 errors / 17 skipped；
- 17 项全部是 M0-01 已冻结的 15 个边界 Disabled 和 2 个真实 OCR Disabled。

### 5.3 模块和应用上下文回归

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl document-service '-Dtest=DocumentServiceImplTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -DskipTests compile
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service '-Dtest=AllModelsTest' '-Dai.models.openai[qwen3.7-max-2026-05-20].enabled=false' '-Dai.models.openai[glm-5].enabled=false' '-Dai.models.openai[kimi-k2.5].enabled=false' '-Dai.models.openai[MiniMax-M2.5].enabled=false' '-Dspring.rabbitmq.listener.simple.auto-startup=false' test
```

- `DocumentServiceImplTest`：4/4，零失败/错误/跳过；使用既有 mock，不是网络文档服务验证；
- `file-service compile`：`BUILD SUCCESS`；没有运行真实文件服务；
- `AllModelsTest`：1/1，零失败/错误/跳过；进程级配置使用非秘密合成占位，四个远端模型全部禁用，真实模型成功数 0/4；
- 应用上下文日志中的 Redis 连接失败和 MinIO client 构造不构成 Redis/MinIO 验证。

### 5.4 冻结默认/Integrated 门禁

```powershell
.\scripts\review-m0\run-m0-acceptance.cmd
.\scripts\review-m0\run-m0-acceptance.cmd -Integrated
```

- 默认：26/26、零失败/错误/跳过，`ASSET_VERIFIED`，退出 0；
- Integrated：26/26 资产自检、零失败/错误/跳过，`PENDING_INTEGRATION`；批处理内部 `%ERRORLEVEL%` 经外层捕获为 2；
- Integrated `readinessMissing` 仍为生产 adapter、精确 integrated selection 和静态根未声明；15 个 P0 生产计数为 `null`；
- 没有修改验收资产追求 `AUTO_VERIFIED`。

## 6. 外部依赖边界

| 依赖 | 实际状态 | 可声称结论 |
| --- | --- | --- |
| H2 MySQL mode | `RUN` | 真实进程内 JDBC/DDL/事务/CAS/并发测试；不是 MySQL 8 |
| MySQL 8 | `NOT_RUN` | 生成列、check、元数据大小写、循环 FK、锁和死锁语义均未验证 |
| RabbitMQ/外部队列 | `NOT_USED` | 当前仅数据库租约/轮询 SPI，没有真实队列验证 |
| Redis | `NOT_RUN` | 上下文连接失败日志不是验证 |
| MinIO/文件服务 | `NOT_RUN` | client 构造、compile 和 mock 单测不是网络链路 |
| 文档服务网络/OCR | `NOT_RUN` | 仅现有 mock/本地 parser 基线；真实 OCR 仍 Disabled |
| 远程模型 | `NOT_RUN` | 四个模型禁用，成功数 0/4 |
| 浏览器/双用户 JWT | `NOT_RUN` | 没有 HTTP 或浏览器端到端 |

没有把任何 `NOT_RUN/NOT_USED` 写成已验证。

## 7. M0-03/M0-04 准确依赖面（修复并复核前禁止使用）

以下类型和入口确实存在，但 B-01 至 B-05 修复并由新的 Wave 1 集成门复核前，不构成可批准的共同基线。

### 7.1 M0-03 相关

- 领域类型：`OfficialBasis`、`OfficialBasisSourceLocator`、`ChecklistRequirement`、`ChecklistRevision`、`RequirementRevision`、`ChecklistSnapshot`、`RemotePreview`、`RemoteConsent`、`RemoteCall`、`InputDiagnostic` 及冻结枚举/错误码；
- Case 入口：`ReviewCaseStore.findOwned(...)`、`findChecklistSnapshotOwned(...)`、`advanceRevisionOwned(...)`、`publishChecklistSnapshotOwned(...)`；
- Job 入口：`ReviewM0JobHandler.jobType()/handle(...)`、`ReviewM0JobSubmissionService.submit(...)`、`ReviewM0JobStore.acquireNext/saveCheckpoint/requestCancelOwned/completeWithResult(...)`；
- 远程入口：`ReviewM0RemoteCallStore.beginSendIntent/recordResponse/markOutcomeUnknown/findByJob(...)`，其中 `beginSendIntent` 当前被 B-03 阻塞；
- 表：`review_m0_official_basis`、`review_m0_checklist_revision`、`review_m0_requirement`、`review_m0_requirement_revision`、`review_m0_checklist_snapshot`、`review_m0_remote_preview`、`review_m0_remote_consent`、`review_m0_input_diagnostic`、Job/checkpoint/remote_call/idempotency 表；
- 事务：M0-03 必须用同一 Spring `DataSourceTransactionManager` 的外层 REQUIRED 事务写自己的表并调用 Case/Job Store，不能把业务写入与修订/OUTDATED 拆开。

### 7.2 M0-04 相关

- 领域类型：`SubmissionSource`、`SubmissionMaterial`、`MaterialAssignment`、`UploadConfirmation`、`LogicalFileName`、`DigestEntry`、`ConfirmedAssignmentFingerprint`、`LogicalNameFingerprint`；
- 修订入口：`ReviewCaseStore.advanceRevisionOwned(...)` 的 `MATERIAL_SET/ASSIGNMENT/NAME`；
- 表：`review_m0_submission_source`、`review_m0_material`、`review_m0_material_assignment`、`review_m0_upload_confirmation`、`review_m0_input_diagnostic`；
- 事务：材料/分配/逻辑名写入必须与修订推进和当前报告 `OUTDATED` 位于同一外层 REQUIRED 事务；
- 阻塞：B-04 修复前，不能依赖当前 `SubmissionMaterial`/DDL 表达 ZIP 容器-only 不变量；M0-04 仍必须先实现 owner-aware 文件快照，不能把既有 fileId 下载链路当成 owner-safe 适配器。

## 8. 后续要求

1. M0-02 所有者先在自己的实现任务中修复 B-01 至 B-05，并增加真实 JDBC 负例；本集成任务不代修；
2. 至少新增跨 owner Job 提交、跨 Case Report 发布、跨 Case/过期 Consent、ZIP 容器材料拒绝和已安装版本缺 FK 的 strict validate 断言；
3. 修复提交必须继续保持 M0-02 冻结所有权，不修改公共 POM/YAML、Agent 热点、门禁资产、Web 或冻结契约；
4. 重新执行本交接第 5 节全部门禁，并保持真实 MySQL/外部服务为 `NOT_RUN`，除非确实运行并留有证据；
5. 只有新的独立 Wave 1 集成审计通过并提交 `READY` handoff 后，才能给 M0-03 与 M0-04 发布共同起始 SHA。

本任务到此停止；没有开发 M0-03/M0-04，也没有为让测试通过而修改 M0-02 或验收资产。
