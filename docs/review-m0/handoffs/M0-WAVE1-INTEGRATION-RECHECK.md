# Review M0 Wave 1 FIX1 独立阶段门复核交接

## 1. 结论与范围

- 日期：`2026-08-15`
- 状态：`READY_FOR_M0_03_M0_04`
- 固定复核起点：`a86250dc9a1da70635b7061d0330651e77c0f23c`
- FIX1 父提交：`3bfcefe73143bb74b9587b01af5b6314225b3494`
- M0-02 提交：`b1c1f59c585fdbfab7e834b18ca88a3544d028e8`
- Wave 1 基线：`d9b5f2d2ae18d46fbc1d7483ee86662ee40b8f90`
- 复核分支：`review/m0-wave1-recheck-019ff65c`
- 复核 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-wave1-recheck-019ff65c`
- 交接提交：本文档所在的唯一 handoff commit；提交自身不能稳定记录自己的 SHA，完整 SHA 由最终交付报告给出。
- 生产代码、测试、迁移、POM、YAML、冻结契约、门禁、验收资产、Web、`docs/PROGRESS.md` 修改：无。
- 本交接以外新增文件：无。

本次只独立复核 M0-02 FIX1 是否关闭上次 Wave 1 阶段门的五项冻结不变量缺陷。没有开发或修改 M0-03/M0-04。源码审计、真实 Spring JDBC 事务、进程内 H2 MySQL-mode 负例、并发竞争和完整冻结回归均通过，因此解除 M0-03/M0-04 阻塞；本 handoff commit 可作为两项任务的共同起始 SHA。

`READY_FOR_M0_03_M0_04` 只表示 Wave 1 的 M0-02 阶段门通过，不表示 Review M0 已完成最终集成、`AUTO_VERIFIED`、真实 MySQL 8 验证或人工验收。

## 2. 提交拓扑、patch 与所有权

只读拓扑核对结果：

```text
a86250dc9a1da70635b7061d0330651e77c0f23c
  <- 3bfcefe73143bb74b9587b01af5b6314225b3494
  <- b1c1f59c585fdbfab7e834b18ca88a3544d028e8
  <- d9b5f2d2ae18d46fbc1d7483ee86662ee40b8f90
```

- 四个提交的唯一父节点逐个精确匹配指定链；
- `merge-base(d9b5f2d, a86250d)` 精确为 `d9b5f2d2ae18d46fbc1d7483ee86662ee40b8f90`；
- `d9b5f2d..a86250d` 提交跨度为 3；
- 独立复核 worktree 从 `a86250d` 创建，没有修改当前 checkout 或其他 worktree；
- FIX1 相对父提交恰好改动 16 个文件：15 个 M0-02 model/store/job/DDL 源码或测试文件，加 `M0-02-FIX1.md`；授权路径外文件为 0；
- 根/`ai-service` POM、公共 YAML、`AgentExecutionService.java`、`AgentToolRegistry.java`、冻结文档、`M0-WAVE1-INTEGRATION.md`、`scripts/review-m0/**`、acceptance 资产、Web、`docs/PROGRESS.md` 差异均为 0；
- Review M0 生产包对 Agent execution、Conversation/Memory、MCP、RAG/Qdrant 和测试替身的禁止引用扫描均为 0；
- `git diff --check 3bfcefe..a86250d` 通过。

冻结枚举、HTTP JSON、错误码、状态转换、证据语义和 M0-03/M0-04 所有权没有漂移。ZIP 仍保留在 `FormalFileType` 和 `review_m0_submission_source` 的六类顶层白名单中；FIX1 只把可用 `SubmissionMaterial` 收紧为五类非容器叶子格式。

## 3. 原 45 项测试未被删除或弱化

- 对 `b1c1f59` 与 `a86250d` 的 M0-02 测试源码做方法名集合比较：基线 45 个方法名全部仍存在，删除数为 0；当前增加 18 个方法名，其中 2 个是测试 helper，参数化外键负例产生 4 个 discovered，因此总 discovered 增量为 19。
- 测试 diff 中没有删除原测试方法；唯一被替换的旧断言是 RemoteCall Store 接口从无 owner 的 `findByJob(jobId)` 改为更严格的 `findOwned(ownerId, caseId, jobId)`，空结果断言仍保留，并增加异主/异 Case 为空断言。
- 原有 fixture 构造只为新增 owner、input fingerprint、事务管理器和 consent/preview 绑定参数作机械扩展，没有把异常断言改成成功、没有移除数据库计数/CAS/状态断言，也没有新增 `Disabled`。
- 当前精确套件实际为 64/64、零失败/错误/跳过；相对原 45 discovered 正好新增 19 discovered。

## 4. 五个旧阻塞项独立复现

### 4.1 B-01：跨 owner Case 创建或复用 Job

定向执行：

- `crossOwnerCaseSubmissionIsNotFoundAndLeavesNoJobOrIdempotencySideEffect`
- `concurrentCrossOwnerSubmissionsAllFailWithoutCreatingProbeableState`

实际结果：攻击者 owner 对另一 owner 的 Case 提交 Job，单次与两个并发调用均得到 `REVIEW_M0_CASE_NOT_FOUND`；没有成功或可探测的异主资源。

数据库无副作用证据：

- 测试直接断言 `review_m0_job=0`、`review_m0_idempotency=0`；
- 额外独立 JShell/JDBC 探针在失败前后读取 Case 的 `basis_revision/checklist_revision/material_set_revision/assignment_revision/name_revision/version/current_report_id`，结果逐字段完全相同；
- 探针输出为 `OWNER_PROBE=REVIEW_M0_CASE_NOT_FOUND`、`OWNER_PROBE_JOB_ROWS=0`、`OWNER_PROBE_IDEMPOTENCY_ROWS=0`、`OWNER_PROBE_CASE_UNCHANGED=true`。

持久化边界审计：Job Store 使用 `INSERT ... SELECT FROM review_m0_case WHERE case_id=? AND user_id=?` 原子写入，不依赖 Controller/调用方预检查；幂等 claim、Job 创建和幂等 complete 由 `ReviewM0JobSubmissionService` 的同一 `TransactionTemplate` 包围，owner 不匹配导致整个 claim 回滚。

### 4.2 B-02：Case A 的租约/Job 发布 Case B 报告

定向执行：

- `crossCaseAndOwnerPublicationRollsBackReportJobPointerAndIdempotencyState`
- `reportInputFingerprintMustEqualTheLeasedJobAndFailureHasNoPublicationSideEffect`
- `succeededJobCannotBeIdempotentlyReusedToProbeAnotherCaseReport`

实际结果：Case A 的已租赁 Job 无法发布 Case B 报告，异主表现为 `REVIEW_M0_RESOURCE_NOT_FOUND`；Job input fingerprint 不匹配为版本冲突；已成功 Job 也不能被用于探测另一 Case。

数据库无副作用证据：

- 失败后 `review_m0_report=0`，没有 finding/evidence；
- 已租赁 Job 的完整对象（状态、租约、version、result）保持不变，未写入结果引用；
- Case B 的完整 Case 对象和 `currentReportId` 保持不变；
- 幂等记录的 `state/response_status/response_type/response_id` 前后相同；
- 已成功 Job 的跨 Case 重放失败后，目标 Case 报告数仍为 0、目标 `currentReportId=null`。

持久化边界审计：`JdbcReviewReportStore.publishOwned` 自身增加覆盖完整发布的外层 `TransactionTemplate`，并在持锁事务中核验 Job Case/owner/type/input fingerprint、租约或成功结果绑定、upload confirmation 的 Case/owner/snapshot/material revision；嵌套 Job Store 事务为默认 `REQUIRED`。任一校验、报告/finding/evidence 写入、Case 指针 CAS 或 Job 终态 CAS 失败都会整体回滚，不存在捕获异常后部分提交。

### 4.3 B-03：RemoteConsent 完整绑定与并发消费

定向执行覆盖：

| 不匹配/竞争 | 实际结果 | 无副作用证据 |
| --- | --- | --- |
| owner + caseId | `RESOURCE_NOT_FOUND` | consent 未消费，remote call=0，双方 owner-aware 查询均为空 |
| 同 Case 但 consent/preview owner 不同 | `RESOURCE_NOT_FOUND` | consent 未消费，remote call=0 |
| Case 当前 basis revision 已变化 | `REMOTE_PREVIEW_STALE` | consent 未消费，remote call=0 |
| preview 已 `INVALIDATED` | `REMOTE_PREVIEW_STALE` | consent 未消费，remote call=0 |
| operation 不匹配、digest 相同 | `REMOTE_CONSENT_REQUIRED` | consent 未消费，remote call=0 |
| request/consent/preview payload digest 不匹配 | `REMOTE_PREVIEW_STALE` | consent 未消费，remote call=0 |
| consent 已消费但没有该逻辑 Job 的匹配 remote call | `REMOTE_CONSENT_CONSUMED` | 原 consumedByJobId 不变，remote call=0 |
| 两个逻辑 Job 并发消费同一 consent | 恰好 1 成功、1 `REMOTE_CONSENT_CONSUMED` | consent winner 与唯一 remote call jobId 精确相同，remote call 总数=1 |

持久化边界审计：SEND_INTENT 在同一事务内先锁定有效 `CHECKLIST_EXTRACTION` Job/Case，再 `FOR UPDATE` 读取 consent+preview；owner、caseId、operation、current revision、preview `CONFIRMED`、三方 digest、逻辑 Job 和未消费状态全部在写入边界核验。消费使用包含完整绑定字段与 `consumed_by_job_id IS NULL` 的 CAS，随后同事务插入唯一 remote call；失败事务不会留下 consent 消费或 call 行。公共读取只保留 `findOwned(ownerId, caseId, jobId)`。

### 4.4 B-04：ZIP 顶层容器与材料单元分离

定向执行：

- `zipRemainsAFormalInputContainerButCannotBecomeAMaterialUnit`
- `zipSourceIsSupportedButDatabaseRejectsTheContainerAsAMaterialUnit`
- `ordinarySourceHasOneTopLevelMaterialWhileZipAllowsDistinctLeavesOnly`

实际结果：

- `.ZIP` 仍映射为 `FormalFileType.ZIP`，ZIP `SubmissionSource` 可构造，DDL 的 source 类型约束继续接受 ZIP；
- `TOP_LEVEL_FILE + ZIP` 和 `ZIP_ENTRY + ZIP` 都不能构造成 `SubmissionMaterial`；DDL 同样拒绝把 ZIP source 容器插成材料，失败后该 source 的 material 行数为 0；
- ZIP source 下 PDF/PNG 不同叶子正常入库，重复规范路径继续被拒绝；
- 额外独立模型探针逐项构造 `PDF/DOCX/XLSX/JPG/PNG` 顶层普通材料，五项均输出 `LEAF_ACCEPTED`；ZIP 输出 `ZIP_MATERIAL=REJECTED`。

因此 FIX1 没有把 ZIP 改成“不支持的顶层格式”，也没有误伤五类正式非容器材料。ZIP 展开、危险条目和下载仍属于 M0-04，本次没有提前实现。

### 4.5 B-05：V002 关键外键 strict validate

定向执行：

- `strictValidationRejectsEveryMissingV002ForeignKey`：四个参数化 discovered，逐个删除四类 V002 外键后均拒绝；
- `strictValidationUsesForeignKeyStructureRatherThanOnlyConstraintName`：保留原约束名但把目标改成错误表，仍拒绝；
- `strictValidationRejectsCascadingRulesOnV002ForeignKeys`：结构正确但改为 `ON DELETE CASCADE`，仍拒绝；
- `initializesAllFrozenTablesAndRepeatedInitializationIsIdempotent`：完整 schema 两次初始化通过，22 张表、2 个版本行；
- `resumesVersionTwoAfterAInterruptedCyclicReferenceMigration`：删除 version 2 和一个 V002 外键后，迁移恢复并 strict validate 通过。

实现审计确认 strict validate 使用 JDBC metadata 的源表/列、目标表/列、`KEY_SEQ=1` 和 restrictive update/delete rule 匹配，不读取固定约束名判定通过。迁移恢复仍可按既有名称补装，但最终严格校验独立于名称。

## 5. 实际测试、门禁与计数

以下命令均从本独立 worktree 根执行。

### 5.1 M0-02 全部精确测试

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service '-Dtest=com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test' test
```

- 64 discovered / 64 passed / 0 failures / 0 errors / 0 skipped；
- `BUILD SUCCESS`，退出 0；
- 使用真实 Review SQL、Spring JDBC/事务和进程内 H2 MySQL-mode；并发负例使用真实线程竞争。

五项缺陷的显式定向选择另运行 23 discovered / 23 passed / 0 failures / 0 errors / 0 skipped，退出 0；23 包含四个参数化缺外键调用、正向 schema/恢复用例和五项缺陷的所有负向/并发方法。

### 5.2 M0-01 冻结精确基线

```powershell
.\mvnw.cmd -q -pl document-service -am dependency:build-classpath '-Dmdep.outputFile=target/m0-pb-runtime-classpath.txt' '-Dmdep.includeScope=runtime'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service '-Dtest=com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest' test
```

- classpath 准备退出 0；
- 99 discovered / 82 passed / 0 failures / 0 errors / 17 skipped；
- 17 项仍是冻结的明确 Disabled：15 个待实现/外部边界和 2 个真实 OCR 边界；没有新增 skip 或把未验证能力改成通过。

### 5.3 模块与应用上下文

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl document-service '-Dtest=DocumentServiceImplTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -DskipTests compile
```

- `DocumentServiceImplTest`：4 discovered / 4 passed / 0 failures / 0 errors / 0 skipped，退出 0；既有 mock 单元测试，不是网络文档服务验证；
- `file-service compile`：`BUILD SUCCESS`，退出 0；不是文件服务/MinIO 运行验证。

`AllModelsTest` 使用 `synthetic-not-real` 非秘密占位值、四个精确 indexed 模型禁用属性和 RabbitMQ listener 禁用属性运行：

- 1 discovered / 1 passed / 0 failures / 0 errors / 0 skipped，退出 0；
- 日志逐个确认四个模型 `enabled=false`，真实模型成功数为 0/4；
- Redis/HNSW 和监控后台连接失败没有使 context 失败，也不构成 Redis 验证；MinIO client 只使用非秘密占位配置构造，没有执行对象存储链路。

### 5.4 默认与 Integrated 验收门

```powershell
.\scripts\review-m0\run-m0-acceptance.cmd
cmd.exe /d /c "call .\scripts\review-m0\run-m0-acceptance.cmd -Integrated"
```

- 默认：26 discovered / 26 passed / 0 failures / 0 errors / 0 skipped；敏感信息门扫描 1012 个 Git 可见文本文件无命中；`ASSET_VERIFIED`；退出 0；
- Integrated：26/26 资产自检，零失败/错误/跳过；报告为 `PENDING_INTEGRATION / NOT_READY`、`finalAcceptance=false`；原生批处理边界明确捕获 `INTEGRATED_GATE_EXIT=2`；
- Integrated 的三个 `readinessMissing` 仍为 production adapter、精确 integrated selection 和静态扫描根未声明；15 个 P0 生产计数全部为 `null`，没有把 Wave 1 阶段门冒充最终生产验收。

直接由当前执行宿主调用 `.cmd -Integrated` 时，宿主曾把任意非零结果显示为 1；随后通过原生 `cmd.exe` 显式捕获脚本内部退出码为 2，报告内容和 `PENDING_INTEGRATION` 状态相同。冻结要求以该原生批处理退出码复核通过。

## 6. 静态门禁与工作树

- FIX1 授权路径外文件：0；禁改热点差异：0；冻结契约漂移：0；
- Review M0 生产包禁止依赖：0；生产包 H2/test fake/stub/Mockito 标记：0；
- `git diff --check 3bfcefe..a86250d`：通过；
- 默认与 Integrated 门内敏感信息检查：均扫描 1012 个 Git 可见文本文件，无 secret 或 fixture PII 命中；
- 新增本文后再次执行 `git diff --check`、敏感信息检查和变更范围检查；结果记录在最终提交前复核中；
- 测试前和只读复核完成后，worktree 除规定新增本文外无 Git 可见变化；构建产物均在已忽略的 `target` 下。

## 7. 外部依赖与未验证项

| 依赖/环境 | 实际状态 | 可声称边界 |
| --- | --- | --- |
| H2 MySQL mode | `RUN` | 真实进程内 DDL/JDBC/事务/锁/CAS/并发负例；不是 MySQL 8 |
| MySQL 8 | `NOT_RUN` | metadata 大小写、生成列、check/FK、锁、死锁与多连接生产语义未验证 |
| RabbitMQ/外部队列 | `NOT_RUN` | M0-02 仍是数据库租约/轮询 SPI |
| Redis | `NOT_RUN` | 应用上下文连接失败日志不是验证 |
| MinIO/真实 file-service | `NOT_RUN` | compile/client 构造不是网络链路或 owner 验证 |
| 真实 document-service/OCR | `NOT_RUN` | 只运行既有 mock 与本地 parser 基线；真实 OCR 仍 Disabled |
| 远程模型/真实远程发送 | `NOT_RUN` | 四个模型禁用，成功数 0/4；未发送任何载荷 |
| 浏览器/双用户 JWT HTTP | `NOT_RUN` | 没有 Controller 或端到端；M0-06/人工验收范围 |
| 多节点、数据库中断、MySQL 死锁重试 | `NOT_RUN` | 本地并发测试不能外推生产故障行为 |

仍未验证或未实现：M0-03 官方依据/候选/隐私与真实 RemoteConsent 编排，M0-04 owner-aware 文件适配/ZIP 安全展开/下载，M0-05 确定性报告，M0-06 API/装配，最终 M0-07 Integrated adapter 与 P0/P1 生产组合门，以及 `P-01` 至 `P-13` 产品待确认项。

## 8. M0-03/M0-04 共同起点

阶段门决定：`READY_FOR_M0_03_M0_04`。

M0-03 与 M0-04 必须从本文所在最终 handoff commit 的完整 SHA 创建各自独立分支/worktree；不得直接从 `a86250d` 绕过本复核记录。两任务继续遵守冻结所有权：

- M0-03 只实现 checklist/privacy/extraction 范围，并通过现有 owner-aware Store、事务和 RemoteConsent 边界接入；
- M0-04 只实现 material/file/archive 范围，ZIP 作为 source 容器展开五类叶子，不能把 ZIP 自身插成材料；
- 双方不得修改 M0-02 所有权文件；如需 SPI 兼容新增，只在 handoff 提出，由协调者形成单一适配提交；
- 不得把本次 H2 结论写成真实 MySQL 8 验证。

本复核到此停止；没有开发 M0-03/M0-04。
