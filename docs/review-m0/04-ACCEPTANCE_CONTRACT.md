# Review M0：验收契约

## 1. 状态与结论

当前状态：`CONTRACT_FROZEN / IMPLEMENTATION_NOT_STARTED`。

自动验收将来只允许输出：

- `ASSET_VERIFIED`：只验证 fixture、Schema 和门禁自身；
- `PENDING_INTEGRATION`：生产实现或组合前置缺失，必须非零退出；
- `AUTO_VERIFIED / MANUAL_PENDING`：全部确定性自动门禁通过，但真实人工/外部依赖尚未确认；
- `HUMAN_ACCEPTED`：只能在用户实际执行人工清单并记录结果后使用。

不得用文档存在、代码编译、测试跳过或资产模式通过声称 `AUTO_VERIFIED`。

## 2. 统一入口和机器报告

M0-07 应交付：

```text
scripts/review-m0/run-m0-acceptance.cmd
scripts/review-m0/run-m0-acceptance.ps1
```

默认模式只验证资产；`-Integrated` 执行生产组合门。统一入口必须在任意当前目录工作，将临时文件写入任务专用目录并在正常/失败路径清理。

机器报告固定输出到调用者显式指定的目录；未指定时使用 Git 忽略的任务临时目录。报告最小 JSON：

```json
{
  "schemaVersion": "REVIEW_M0_ACCEPTANCE_REPORT_V1",
  "contractVersion": "REVIEW_M0_CONTRACT_V1",
  "gateType": "FINAL_ACCEPTANCE",
  "mode": "INTEGRATED",
  "status": "AUTO_VERIFIED",
  "manualStatus": "MANUAL_PENDING",
  "finalAcceptance": true,
  "baselineCommit": "full-sha",
  "startedAt": "2026-08-13T08:00:00.000Z",
  "finishedAt": "2026-08-13T08:10:00.000Z",
  "counts": {
    "tests": 0,
    "passed": 0,
    "failed": 0,
    "errors": 0,
    "skipped": 0
  },
  "p0": {
    "missingBeforeUploadConfirmation": 0,
    "deterministicFindingsWithoutRuleEvidence": 0,
    "deterministicFindingsWithoutMaterialEvidence": 0,
    "systemErrorsReportedAsMissing": 0,
    "cannotConfirmReportedAsMissing": 0,
    "originalFileMutations": 0,
    "objectKeyMutations": 0,
    "remoteSendsWithoutMatchingConsent": 0,
    "candidateChecklistsUsedForReview": 0,
    "nestedArchivesAccepted": 0,
    "crossUserSuccessfulReads": 0,
    "duplicatePublishedReports": 0,
    "staleReportsMarkedCurrent": 0,
    "readyReportsWithIgnoredFindings": 0,
    "incrementalFullMismatches": 0
  },
  "external": {
    "realMysql": "NOT_RUN",
    "realFileService": "NOT_RUN",
    "realDocumentService": "NOT_RUN",
    "realRemoteModel": "NOT_RUN",
    "realBrowser": "NOT_RUN"
  },
  "readinessMissing": [],
  "unverified": []
}
```

规则：

- `AUTO_VERIFIED` 要求 `failed=errors=skipped=0`，全部 P0 计数为 0，`readinessMissing=[]`。
- 任何冻结目标被跳过，状态最多为 `PENDING_INTEGRATION`。
- `finalAcceptance=true` 只用于集成后的最终门；阶段门必须 `gateType=STAGE_GATE`、`finalAcceptance=false`、`manualStatus=NOT_READY`。
- `HUMAN_ACCEPTED` 不能由自动脚本自行写入。
- 报告只含元数据、计数和不含正文的未验证码。

## 3. P0 硬门禁

### A. 范围和清单发布

1. `CANDIDATE_DRAFT` 作为正式审查输入必须被拒绝。
2. 官方提取项、用户新增项、用户改写项的来源谱系保持区分。
3. 改写官方提取项保留官方定位并记录用户修订，不能把改写文字冒充官方原文。
4. 停用项目保留历史，且不进入新正式快照。
5. 未逐项确认或存在 `NEEDS_USER_DECISION` 时不能发布正式清单。
6. 依据变化使旧清单 `STALE_BASIS`，不能提交正式审查。
7. 输入正文中的错别字、漏填、资格、签章日期、跨材料差异、证明支持或写作质量均不产生 M0 发现。

### B. 上传确认与缺失真值表

至少固定以下 fixture：

| 当前清单 | 上传确认 | 可用已分配数 | 潜在 `CANNOT_CONFIRM` | 预期 |
| --- | --- | ---: | ---: | --- |
| 未确认/草稿 | 任意 | 任意 | 任意 | 拒绝，不生成报告 |
| 已确认 | 无 | 0 | 0 | `409 REVIEW_M0_UPLOAD_NOT_CONFIRMED`，`MISSING=0` |
| 已确认 | 当前 | 少于 `minimumCount` | 0 | 一个 `MISSING`，两侧证据齐全 |
| 已确认 | 当前 | 少于 `minimumCount` | 大于 0 个当前提交输入 | 输出全部 `CANNOT_CONFIRM`，整份报告 `MISSING=0` |
| 已确认 | 当前 | 等于/大于 `minimumCount` | 0 | 无 `MISSING` |
| 已确认 | 旧材料修订 | 任意 | 任意 | 拒绝，不生成报告 |

`MISSING` 的材料集合证据必须覆盖当前上传确认、全部材料、当前已确认分配和计数。只测试结果文字包含“缺少”不算通过。

### C. 不确定性和系统错误

1. 加密、损坏、解析失败、OCR 不足、类型不一致、嵌套 ZIP、安全上限和源文件已不可用分别得到冻结 `CannotConfirmReason`。
2. 只要当前提交材料集合有一个未决 `CANNOT_CONFIRM`，整份报告的 `MISSING` 必须为 0；不能猜测材料用途。
3. 文件服务网络失败、数据库失败、队列失败、模型超时/鉴权/限流、未知异常分别使 Job `FAILED` 或 API 503。
4. 上述系统失败不创建正式报告、`MISSING` 或 `CANNOT_CONFIRM` 业务发现。
5. 重试后成功只能发布一个结果。

### D. 文件格式、ZIP 和非破坏性

1. PDF/DOCX/XLSX/JPG/PNG 普通文件可形成材料单元；ZIP 可形成容器但不直接满足项目。
2. ZIP 中五类叶子可各自形成材料；嵌套 ZIP 和其他扩展不得成为可用材料。
3. 路径穿越、绝对路径、NUL、重复 NFC 规范路径、加密条目、CRC/摘要不符和 zip bomb 安全上限均拒绝优先。
4. 附加、逻辑改名、审查和下载前后，原文件字节摘要、原始文件名和文件服务 `objectKey` 完全不变。
5. ZIP 单份下载只返回目标条目，普通文件只返回目标文件，均使用当前逻辑名。
6. 文件名中 CR/LF、路径分隔、点目录和控制字符不能注入响应头或路径。

### E. 命名和证据

1. `EXACT`、安全 `REGEX` 和 `NONE` 项目规则测试；`EXACT/REGEX` 覆盖显式大小写敏感/不敏感，缺失 `caseSensitive` 时拒绝发布；全局安全命名始终执行。
2. 扩展名大小写比较不敏感，实际类型不一致为 `CANNOT_CONFIRM/TYPE_MISMATCH`，不是普通命名问题。
3. 每个 `FILE_NAME_NONCOMPLIANT` 有具体规则 ID 和材料快照证据。
4. 正则编译失败或超时不能包装成不合规发现，应阻止清单发布或使 Job 系统失败。

### F. 远程发送

1. 默认 `LOCAL_ONLY` 不构造或调用远程客户端。
2. 远程模式没有预览确认、revision 不同、operation 不同、digest 不同或授权已被其他任务消费时都不能发送。
3. 编辑预览后旧确认失效。
4. 实际发送字节摘要与确认的 `payloadDigest` 相同；审计能证明一次逻辑 Job 最多一次远程发送。
5. Worker 崩溃恢复不重复已成功取得响应的远程调用；`SEND_INTENT` 后结果未知时必须 `REMOTE_OUTCOME_UNKNOWN`，不自动重发。
6. 远程响应只能产生候选草稿。
7. 日志、错误和机器报告没有预览正文、远程载荷/响应或凭据。

### G. 幂等、恢复和取消

1. 每个写端点同键同载荷重放返回原资源；同键异载荷 409。
2. Worker 在 `QUEUED`、处理中各 checkpoint、结果发布前后崩溃的 fixture 均能恢复且无重复副作用。
3. 取消 `QUEUED`、`RUNNING` 和终态任务满足冻结状态机。
4. `CANCEL_REQUESTED` 后不开始新的材料单元或远程调用；`CANCELLED` 无正式结果。
5. 两个 Worker 竞争同一租约只能有一个推进并发布。
6. `(caseId,inputFingerprint)` 相同的重复审查最多一个报告。

### H. 过期与增量

1. 依据、正式清单、材料集、分配、逻辑名和引擎版本的每个变化分别使旧当前报告在事务提交时 `OUTDATED`。
2. `OUTDATED` 不可恢复为 `CURRENT`；新审查创建新报告。
3. 单文件逻辑改名只重算对应命名发现；状态/摘要/分配变化扩大齐全性影响集合。
4. 对同一最终输入，增量与完整审查的规范化发现、证据、计数和 assessment 完全一致。
5. 未声明输入变化使增量失败为 `REVIEW_M0_INCREMENTAL_BASE_MISMATCH`，不静默回退部分结果。

### I. 忽略、Ready 与授权

1. `IGNORE` 后发现和证据仍可读，`ignoredCount` 增加，assessment 仍是 `M0_NOT_READY`。
2. 重复 `IGNORE` 幂等返回原决定；本契约不提供撤销忽略操作。
3. `M0_READY` 报告的三类发现计数和 ignored 数都为 0，且 freshness 为 `CURRENT`。
4. 异主案例、依据、材料、预览、任务、报告和下载都返回 404；无任何成功侧信道。
5. 请求体 `userId/admin` 未知字段被拒绝，不能影响所有者。

## 4. P1 功能门禁

- 所有冻结 HTTP 端点、字段、枚举和错误码有正/负契约测试；
- 正式清单和报告 JSON 可以往返并通过 digest 校验；
- 稳定输入指纹跨 JVM/进程/数据库读取顺序一致；
- 大材料清单/ZIP 使用流式读取且受有界内存、超时和安全上限保护；具体容量只记录测量值，不升级为产品承诺；
- 下载 RFC 5987 UTF-8 和 ASCII fallback 在主流客户端解析正确；
- 审计可以从发现回溯规则修订、官方定位、材料摘要和上传确认；
- 本地候选抽取质量不足时明确要求用户编辑，不自动发布；
- API 日志只记录 ID、摘要、数量、耗时、状态和稳定错误码。

## 5. 回归门禁

M0-07 至少复跑：

- 既有 `AgentExecutionServiceTest` 与 `AgentControllerTest`；
- 既有 Conversation/Memory 结构化 API 和最终门中与应用上下文相关的精确回归集合；
- 文件服务上传、下载、元数据和删除的既有精确测试；
- 文档服务解析入口的既有精确测试；
- `AllModelsTest` 仅在明确非真实占位/禁用远程调用的配置下验证应用上下文，并在报告中注明远程成功数不等于真实连通。

精确类名由 M0-01 基线盘点冻结到 handoff；不得以运行整个不稳定外部套件代替 Review 专项，也不得因既有外部依赖缺失删除回归。

## 6. 机器报告和敏感信息门

静态与动态扫描至少禁止以下内容进入 Git、日志、异常和验收报告：

- MinIO、数据库、模型或 JWT 的真实密钥；
- `objectKey`、bucket 内部路径和预签名 URL；
- 官方依据全文、提交材料正文、ZIP 条目正文；
- 脱敏预览正文及用户编辑内容；
- 远程模型请求、响应或完整 Prompt；
- Java 堆栈或数据库连接串。

fixture 必须完全合成，不含真实学生姓名、学号、身份证、成绩、手机号、邮箱、签章或学校私有材料。

## 7. 人工验收清单

自动门通过后，用户至少实际执行并记录：

1. 在真实认证会话创建案例，跨两个用户验证完全隔离；
2. 手动分类三类官方依据，核对候选定位、用户新增/改写/停用谱系；
3. 在未确认上传完毕时尝试审查，确认无 `MISSING`；
4. 确认上传完毕后验证真实缺失项目的两侧证据；
5. 上传加密/损坏材料样本，以及 OCR 不足的官方依据样本，确认 `CANNOT_CONFIRM` 且不转缺失；
6. 上传六类顶层格式和含五类叶子的 ZIP，验证嵌套/非法/危险 ZIP 被阻止；
7. 修改逻辑名并下载普通文件与 ZIP 单条目，核对下载名和字节，确认原件元数据/对象不变；
8. 检查忽略发现仍保留且不能 Ready；
9. 改变官方依据、清单、材料、分配和逻辑名，逐项观察旧报告立即 `OUTDATED`；
10. 运行单文件增量与完整重审，比较结果一致；
11. 在 Job 运行中重启服务和取消任务，验证恢复/取消/无重复；
12. 若用户选择远程模式，逐字核对脱敏预览、编辑、确认、发送摘要和候选-only 结果；未选择则记录 `NOT_RUN_BY_USER`，不能声称远程已验收；
13. 真实 MySQL、文件服务和文档服务链路至少各执行一条成功和一条失败路径；
14. 若产品入口 P-09 已确认并实现，使用真实浏览器完成刷新恢复和错误展示；否则记录 `P-09 PENDING`，不阻塞纯 API M0 自动结论但阻塞 UI 验收声明。

每项记录时间、环境、操作者、输入类别、预期、实际、证据位置和结论；不得在记录中粘贴材料正文或密钥。

## 8. 完成定义

Review M0 可以标为 `AUTO_VERIFIED / MANUAL_PENDING`，仅当：

- M0-01 至 M0-07 全部集成；
- P0/P1、回归、Schema 和敏感信息门均零失败、零错误、零跳过；
- 机器报告满足 Schema 且全部 P0 计数为 0；
- 所有系统/模型失败与 `MISSING/CANNOT_CONFIRM` 分离；
- 未验证外部依赖和待确认产品事实完整列出。

只有人工清单中适用项被用户实际接受后，才能标为 `HUMAN_ACCEPTED`。`P-01` 至 `P-13` 仍待确认的事实不能因为自动门通过而变成已确认；它们应在对应能力启用前解决或继续采用本文冻结的保守路径。
