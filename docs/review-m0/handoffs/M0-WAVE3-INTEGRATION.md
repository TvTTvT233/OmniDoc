# Review M0 Wave 3 独立集成阶段门交接

## 1. 结论

**状态：`STAGE_GATE_FAILED / NOT_READY_FOR_M0_06`。**

审查日期：2026-08-16（Asia/Shanghai）。

M0-05 来源自测 44/44 和全部前序冻结回归均通过，但新增的独立 Wave 3 测试稳定复现两个生产缺陷、三个失败表现：增量审查在合法的同扩展名逻辑改名后保留旧材料证据；增量审查对防御性扩展名变化与完整审查不等价；相同 HTTP 请求与相同 `Idempotency-Key` 在逻辑文件名修订后不能回放原 Job。独立套件结果为 23 discovered / 20 passed / 2 failures / 1 error / 0 skipped。

因此本协调提交只记录失败门、复现测试和审计结果，**不是 M0-06 起始 SHA，不得据此开发或集成 M0-06**。协调者没有修改生产实现，也没有开发 M0-06。

## 2. 基线、拓扑与来源等价性

- 协调起始基线：`c0ba660568651bc5dbc004e35bd54798e5469768`
- 待审来源提交：`2f8cb93d82a0ef9eb914e849f89ae013dd1b7a6d`
- 隔离 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-wave3-gate-01a0061e`
- 隔离分支：`review/m0-wave3-gate-01a0061e`

来源提交唯一父节点就是协调基线，`merge-base` 也是该基线，且提交跨度为 1。因此在隔离 worktree 中使用 `git merge --ff-only 2f8cb93d82a0ef9eb914e849f89ae013dd1b7a6d`，来源 SHA 被原样保留，没有重放、改写或合并提交。来源 patch 为 36 个文件、3812 行新增；从基线到来源和从基线到 fast-forward 后的来源节点完全是同一提交。

来源 patch 只包含：

- `ai-service/src/main/java/com/javaee/aiservice/review/m0/review/**`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/report/**`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/evidence/**`
- 上述包对应的 M0-05 来源测试
- `docs/review-m0/handoffs/M0-05.md`

没有 M0-02/03/04 所有权文件、POM/YAML、Agent、API Controller、前端、冻结契约、M0-07/acceptance 资产、`scripts/review-m0/**` 或 `docs/PROGRESS.md` 越界。两个 handler 仅实现 M0-02 `ReviewM0JobHandler`，并委托共享 `ReviewM0ReviewJobExecutor`；没有复制上游 Job 类型，也没有 Agent、模型、`RemoteConsent`、Conversation/Memory、MCP、Qdrant、H2、Mockito、fake/stub 或 file-service/MinIO 绕过依赖。

## 3. 阻断缺陷

### B-01：name-only 增量复用旧 CANNOT_CONFIRM 材料证据

生产位置：`DeterministicReviewEngine.evaluateIncremental(...)` 第 49-70 行，特别是第 55-60 行的 `plan.nameOnly()` 分支。

复现：

1. 基准材料为 PDF、`status=CANNOT_CONFIRM`、冻结原因 `ENCRYPTED`、`submissionFileName=before.pdf`。
2. 只做 M0-04 允许的同扩展名逻辑改名为 `after.pdf`；内容摘要、材料状态和分配不变。
3. `IncrementalReviewValidator` 正确给出 `nameOnly=true`。
4. 比较增量与当前输入的完整审查规范化结果。

失败测试：`Wave3DeterministicReviewGateTest.nameOnlyRenameOfCannotConfirmMaterialMustRefreshEvidenceAndMatchFullReview`（第 269-286 行）。完整审查的材料证据是 `after.pdf`，增量结果仍是 `before.pdf`。

原因是 name-only 分支只剔除 changed material 的 `FILE_NAME_NONCOMPLIANT`，其余基准发现和证据全部原样复制；随后只重算命名。文件名也是 `CANNOT_CONFIRM` 材料快照的正式证据字段，因此该复用违反同一最终输入下增量/完整审查的证据等价性。

数据副作用：若按当前实现发布，新的 CURRENT 报告可携带旧 `submissionFileName` 的不可变证据，报告表、发现表和证据表会原子地保存一份与当前输入不一致的审计记录。

建议修复所有者：M0-05 实现所有者。修复应让 changed material 的所有受文件名影响证据按当前 manifest 重建，同时仍保持真实的影响集合重算；不得把完整重审伪装成最小增量。

### B-02：name-only 增量可漏掉 TYPE_MISMATCH

同一生产位置和算法还有第二个可观察表现。防御性复现把 manifest 中的 `submissionFileName` 从 `before.pdf` 变为 `after.docx`，而正式类型仍为 PDF。完整审查生成 `CANNOT_CONFIRM/TYPE_MISMATCH`，增量审查返回空发现。

失败测试：`Wave3DeterministicReviewGateTest.nameOnlyExtensionChangeMustNotLoseTypeMismatchComparedWithFullReview`（第 290-305 行）。

M0-04 的正常同服务改名 API 会拒绝此扩展名变化，因此这不是已证明可由正常 API 制造的持久状态；但 M0-05 manifest 与增量 validator 接受该输入，而且完整引擎明确承担防御性 `TYPE_MISMATCH`。当前增量算法在自身输入契约上仍不等价，若该状态来自迁移、适配器或历史数据，就可能错误发布零 CANNOT_CONFIRM、甚至 M0_READY 的报告。

建议修复所有者：M0-05 实现所有者，并由 M0-04 owner 复核正常写路径继续拒绝扩展名变化。

### B-03：HTTP 幂等摘要错误绑定服务器可变快照

生产位置：`FormalReviewSubmissionService.submitOwned(...)` 第 59-97 行。第 69-71 行把客户端 `FormalReviewRequest` 与服务器刚捕获的当前 manifest 合成 `ReviewJobRequestEnvelope`，再把 `envelope.digest()` 当作 M0-02 幂等请求摘要。

复现：

1. 用相同 owner、case、HTTP body 和 `Idempotency-Key` 提交一次正式审查。
2. 对同一材料做 M0-04 允许的 `.pdf` 到 `.pdf` 逻辑改名；原上传确认仍然有效。
3. 原样重放第一次 HTTP 请求与同一个 key。

错误测试：`Wave3FormalReviewIntegrationGateTest.sameIdempotencyKeyAndSameHttpPayloadStillReplaysAfterLogicalNameRevision`（第 284-305 行）。第二次调用抛出 `REVIEW_M0_IDEMPOTENCY_CONFLICT`，而不是返回第一次的 Job。

数据副作用：复现后仍只有 1 个 Job 和 1 个 idempotency 行，没有重复报告/发现；但客户端无法按冻结 API 契约回放原资源。当前实现把服务器侧可变执行快照混入“相同规范请求载荷”的判定，使同一个 HTTP 请求会随服务端状态变化而产生不同摘要。

建议修复所有者：M0-05 实现所有者。客户端规范请求摘要与不可变 Job 执行 envelope 应分离；幂等 claim/replay 应先按规范 HTTP 请求载荷识别原资源，同时 Job 仍保存首次捕获的服务器 manifest。不得削弱 M0-02 对同 key、不同客户端载荷的冲突保护。

## 4. 通过的独立审计项

除上述阻断点外，源码审计、M0-05 来源测试、Wave 3 协调测试和前序精确回归共同确认：

- 正式前置门拒绝 CANDIDATE_DRAFT、STALE_BASIS、未逐项确认、`NEEDS_USER_DECISION`、缺失或旧上传确认；协调持久化复现确认拒绝后 Job/报告/发现/证据/当前报告指针均为 0/空，跨 owner 资源不可见。
- `CANNOT_CONFIRM` 抑制是在整份材料集合级别完成；9 个冻结原因均覆盖，包括未分配和用途未知材料，且整份报告不生成 MISSING。无不确定项且不足 minimumCount 时恰好一个 MISSING，达到 minimumCount 时无 MISSING。
- 文件/数据库/Job/未知系统异常走 Job FAILED/执行异常，不被 catch 后转换为业务发现；取消在组装/发布前再次检查。
- MISSING 与 FILE_NAME 的规则侧和材料侧证据均经过规范化校验；证据不含材料正文、官方全文、objectKey、预览或远程载荷。任一侧缺失时，在任何报告/发现/证据写入和 Job 伪成功之前拒绝。
- `JdbcReviewReportStore.validateBundle(...)` 对不可变 bundle 做纯内存发布前校验，实际 Job 绑定、CURRENT/OUTDATED、报告、发现、证据、Case 指针和 Job 结果写入位于同一个 `TransactionTemplate`。结构校验发生在事务打开前而非事务体内，但它先于任何持久化且没有可竞态外部读取；独立测试验证失败路径零写入。若冻结要求把纯结构校验也字面放入事务体，应由 M0-02 owner 进一步确认，而不能把当前零副作用证明误写成“校验代码位于事务体内”。
- EXACT/REGEX/NONE、显式大小写、扩展名大小写、全局安全命名、TYPE_MISMATCH、非法/超时正则与所有禁止的内容质量类别均符合冻结范围；引擎和 handler 没有构造或调用模型/RemoteConsent。
- `(caseId,inputFingerprint)` 并发发布最多一个报告；报告/发现/证据不可变。Ready 要求 MISSING/CANNOT_CONFIRM/NAME/ignoredCount 全为 0 且 CURRENT。
- IGNORE 是追加、重复幂等、无撤销；证据仍可读，ignoredCount 增加，assessment 仍为 M0_NOT_READY，历史报告行不更新。跨 owner 报告、发现、决定和 Job 均隐藏。
- basis、正式清单、材料集、分配、逻辑文件名和引擎版本的变化通过 M0-03/M0-04/M0-05 精确回归分别验证旧报告 OUTDATED 且不可恢复 CURRENT。
- 输入/材料/分配顺序与重复执行的规范结果稳定；协调测试做了 50 组排列/重复。非 name-only 的材料状态/分配变化与完整审查等价，基准 fingerprint、引擎版本和 changed-material 声明不匹配均返回冻结错误而不静默回退。
- 增量实现不是无条件调用完整审查后冒充最小重算；它确实保留未受影响命名并重算全局齐全性。但 B-01/B-02 证明当前影响集合划分不完整，所以仍不满足冻结的增量等价要求。
- M0-02 的租约竞争、取消、发布事务失败/重试和最多一个报告/结果事件语义继续通过 64/64、FIX1 23/23 与 Wave 3 并发发布复现。没有为本阶段门修改 M0-02 实现。

## 5. 测试命令与结果

Maven 精确套件统一使用：

```powershell
$env:MAVEN_OPTS='-Xms16m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-DargLine=-Xms32m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off' '-Dtest=<selector>' test
```

计数顺序均为 discovered / passed / failures / errors / skipped：

| 套件 | 精确 selector | 结果 | 退出 |
| --- | --- | --- | --- |
| M0-05 来源 | `com.javaee.aiservice.review.m0.review.*Test,com.javaee.aiservice.review.m0.report.*Test,com.javaee.aiservice.review.m0.evidence.*Test` | `44 / 44 / 0 / 0 / 0` | 0 |
| Wave 3 独立门 | `com.javaee.aiservice.review.m0.integration.wave3.*Test` | `23 / 20 / 2 / 1 / 0` | 1 |
| M0-03 | `com.javaee.aiservice.review.m0.checklist.*Test,com.javaee.aiservice.review.m0.privacy.*Test,com.javaee.aiservice.review.m0.extraction.*Test` | `21 / 21 / 0 / 0 / 0` | 0 |
| M0-04 | `com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test` | `76 / 76 / 0 / 0 / 0` | 0 |
| M0-02 | `com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test` | `64 / 64 / 0 / 0 / 0` | 0 |
| M0-02 FIX1 | `M0-WAVE2-INTEGRATION.md` 第 151-156 行冻结的 23 个方法 selector | `23 / 23 / 0 / 0 / 0` | 0 |
| Wave 2 | `com.javaee.aiservice.review.m0.integration.wave2.*Test` | `6 / 6 / 0 / 0 / 0` | 0 |
| M0-01 | `com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest` | `100 / 87 / 0 / 0 / 13` | 0 |

M0-01 前置 classpath 命令退出 0：

```powershell
.\mvnw.cmd -q -pl document-service -am dependency:build-classpath '-Dmdep.outputFile=target/m0-pb-runtime-classpath.txt' '-Dmdep.includeScope=runtime'
```

模块和应用上下文回归：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl document-service -DforkCount=1 '-Dtest=DocumentServiceImplTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -DskipTests compile

$env:DASHSCOPE_API_KEY='synthetic-not-real'
$env:OPENAI_API_KEY='synthetic-not-real'
$env:REDIS_PASSWORD='synthetic-not-real'
$env:RABBITMQ_USERNAME='synthetic-not-real'
$env:RABBITMQ_PASSWORD='synthetic-not-real'
$env:MINIO_ACCESS_KEY='synthetic-not-real'
$env:MINIO_SECRET_KEY='synthetic-not-real'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-DargLine=-Xms32m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off' '-Dtest=AllModelsTest' '-Dai.models.openai[qwen3.7-max-2026-05-20].enabled=false' '-Dai.models.openai[glm-5].enabled=false' '-Dai.models.openai[kimi-k2.5].enabled=false' '-Dai.models.openai[MiniMax-M2.5].enabled=false' '-Dspring.rabbitmq.listener.simple.auto-startup=false' test
```

- `DocumentServiceImplTest`：4 / 4 / 0 / 0 / 0，退出 0；mock 单元测试，不是真实 OCR/网络验证。
- `file-service compile`：成功，退出 0；不是 owner/MinIO 运行验证。
- `AllModelsTest`：1 / 1 / 0 / 0 / 0，退出 0；日志确认四个真实模型均 disabled，真实成功数 0/4。Redis 不可达告警和 MinIO client 本地构造不构成外部验证。

验收命令：

```powershell
cmd.exe /d /v:on /c "call .\scripts\review-m0\run-m0-acceptance.cmd & set gateExit=!ERRORLEVEL! & echo GATE_EXIT=!gateExit! & exit /b !gateExit!"
cmd.exe /d /v:on /c "call .\scripts\review-m0\run-m0-acceptance.cmd -Integrated & set gateExit=!ERRORLEVEL! & echo GATE_EXIT=!gateExit! & exit /b !gateExit!"
```

- 默认：26 / 26 / 0 / 0 / 0；敏感信息门扫描 1126 个文本文件无命中；`ASSET_VERIFIED`；退出 0。
- Integrated：资产仍为 26/26，敏感信息门无命中；`PENDING_INTEGRATION`、`finalAcceptance=false`；内部退出 2。`readinessMissing` 为 production adapter、精确 integrated selection、静态扫描 roots 未声明，符合 M0-06/最终适配与外部接入尚未完成的冻结预期。

禁止依赖/绕过扫描无命中，`git diff --check` 退出 0。最终提交前后均只允许 Wave 3 测试和本交接；无生产 diff。

## 6. 协调提交文件

除 fast-forward 保留的 M0-05 来源 patch 外，协调者仅新增：

```text
ai-service/src/test/java/com/javaee/aiservice/review/m0/integration/wave3/Wave3DeterministicReviewGateTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/integration/wave3/Wave3FormalReviewIntegrationGateTest.java
docs/review-m0/handoffs/M0-WAVE3-INTEGRATION.md
```

没有修改来源测试、来源交接、生产代码、冻结契约、POM/YAML、Agent/API/前端、验收脚本/资产、M0-07 或 `docs/PROGRESS.md`。

## 7. 外部依赖与未验证项

以下均为 `NOT_RUN`，不能由本地 H2/JDBC、mock、编译或 fail-closed 结果推断为可用：

- 真实 file-service owner 证明、字节读取和 MinIO；
- MySQL 8 DDL/事务/并发；
- document-service/OCR/解析器真实网络链路；
- 四个远程模型、RemoteConsent/远程调用；
- 浏览器和前端；
- M0-06 API/最终生产适配器、精确 Integrated 选择器和生产静态扫描根。

M0-05 owner 修复 B-01/B-02/B-03 后，必须从本交接所列协调测试开始重跑，并重新执行完整冻结回归。只有新的独立 Wave 3 门全部通过且形成新的 `READY_FOR_M0_06` 交接，才可发布 M0-06 起始 SHA。
