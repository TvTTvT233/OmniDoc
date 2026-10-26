# Review M0：领域、状态机与不变量

## 1. 聚合边界

`ReviewCase` 是租户隔离和授权聚合根。所有读写必须同时满足认证用户是案例所有者；请求体中的 `userId` 不参与授权。M0 不冻结管理员绕过能力。

聚合内对象：

| 对象 | 主键 | 说明 |
| --- | --- | --- |
| `ReviewCase` | `caseId` | 一个用户的一次材料准备案例 |
| `OfficialBasis` | `basisId` | 用户手动分类的官方依据文件引用及不可变元数据摘要 |
| `ChecklistRequirement` | `requirementId` | 跨草稿修订保持稳定的材料要求身份 |
| `ChecklistRevision` | `checklistRevisionId` | 候选清单的可审计草稿修订 |
| `ChecklistSnapshot` | `checklistSnapshotId` | 用户确认发布的不可变正式清单 |
| `SubmissionSource` | `sourceId` | 对一个顶层普通文件或 ZIP 容器的只读文件引用 |
| `SubmissionMaterial` | `materialId` | 顶层普通文件或 ZIP 叶子条目形成的逻辑材料单元 |
| `MaterialAssignment` | `assignmentId` | 用户确认的材料与清单项目关系 |
| `UploadConfirmation` | `uploadConfirmationId` | 用户声明某清单快照与材料集合已经上传完毕 |
| `RemotePreview` | `previewId` | 可编辑脱敏发送预览及摘要 |
| `RemoteConsent` | `consentId` | 与最终预览摘要绑定的一次任务授权 |
| `ReviewJob` | `jobId` | 候选提取或正式审查后台任务 |
| `ReviewReport` | `reportId` | 对完整输入快照的不可变审查报告 |
| `ReviewFinding` | `findingId` | 报告中的缺失、命名或不能确认项 |
| `FindingDecision` | `decisionId` | 用户对发现的不可变忽略审计事件；撤销能力待确认 |

所有 ID 都是服务端生成的不透明字符串。实现可以使用 UUID/ULID，但具体生成算法不是产品契约；API 消费者不得从 ID 解析时间、用户或类型。

## 2. 枚举冻结

### 2.1 案例与依据

`ProcessingMode`：

- `LOCAL_ONLY`
- `REMOTE_MODEL`

`OfficialBasisRole`：

- `APPLICATION_NOTICE`
- `FILLING_GUIDE`
- `OFFICIAL_TEMPLATE`

`OfficialBasisStatus`：

- `ATTACHED`：已引用，尚未成功形成候选输入；
- `READY`：本地抽取或预处理成功，可用于候选提取；
- `CANNOT_CONFIRM`：输入存在但加密、损坏、OCR 不足或无法可靠处理；
- `DETACHED`：已从当前依据集停用，审计记录保留。

### 2.2 清单

`ChecklistLifecycle`：

- `CANDIDATE_DRAFT`
- `CONFIRMED`
- `STALE_BASIS`
- `SUPERSEDED`

`RequirementOrigin`：

- `OFFICIAL_EXTRACTED`
- `USER_ADDED`

`RequirementAuthorship`：

- `EXTRACTED_TEXT`
- `USER_REWRITTEN`

`RequirementApplicability`：

- `APPLIES`
- `DOES_NOT_APPLY`
- `NEEDS_USER_DECISION`

`FileNameRuleKind`：

- `EXACT`
- `REGEX`
- `NONE`

`NONE` 只表示该清单项目没有项目级命名模式；全局安全命名和扩展名一致性仍然适用。`REGEX` 存储的是用户确认后的完整匹配表达式，必须以安全正则引擎/超时边界执行；不能把正则编译或超时失败包装成文件名不合规。

每个规则同时存 `caseSensitive`：`EXACT/REGEX` 必须为显式布尔值，`NONE` 必须为 `null`。`EXACT` 在 Unicode NFC 后按该布尔值比较完整逻辑名；`REGEX` 按该布尔值执行完整字符串匹配，不做子串匹配。

### 2.3 材料

`FormalFileType`：

- `PDF`
- `DOCX`
- `XLSX`
- `JPG`
- `PNG`
- `ZIP`

`MaterialKind`：

- `TOP_LEVEL_FILE`
- `ZIP_ENTRY`

`SubmissionSourceStatus`：

- `ATTACHED`
- `REMOVED`

`MaterialStatus`：

- `AVAILABLE`
- `CANNOT_CONFIRM`
- `REMOVED`

`AssignmentStatus`：

- `SUGGESTED`
- `USER_CONFIRMED`
- `REJECTED`

只有 `USER_CONFIRMED` 参与齐全性计数。`SUGGESTED` 不得降低缺失数或使报告进入 `M0_READY`。

### 2.4 任务与报告

`ReviewJobType`：

- `CHECKLIST_EXTRACTION`
- `FORMAL_REVIEW`
- `INCREMENTAL_REVIEW`

`ReviewMode`（正式审查请求）：

- `FULL`
- `INCREMENTAL`

`ReviewJobStatus`：

- `QUEUED`
- `RUNNING`
- `CANCEL_REQUESTED`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

`ReviewReportFreshness`：

- `CURRENT`
- `OUTDATED`

`ReviewAssessment`：

- `M0_READY`
- `M0_NOT_READY`

`FindingType`：

- `MISSING`
- `FILE_NAME_NONCOMPLIANT`
- `CANNOT_CONFIRM`

`CannotConfirmReason`：

- `PARSE_FAILED`
- `ENCRYPTED`
- `CORRUPTED`
- `OCR_INSUFFICIENT`
- `TYPE_MISMATCH`
- `NESTED_ARCHIVE_NOT_ALLOWED`
- `ARCHIVE_ENTRY_UNSUPPORTED`
- `ARCHIVE_SAFETY_LIMIT_EXCEEDED`
- `SOURCE_FILE_UNAVAILABLE`

`DiagnosticSubjectType`：

- `OFFICIAL_BASIS`
- `SUBMISSION_SOURCE`
- `MATERIAL`

`FindingDecisionType`：

- `IGNORE`

`EvidenceKind`：

- `OFFICIAL_BASIS_LOCATION`
- `USER_RULE_REVISION`
- `GLOBAL_M0_POLICY`
- `MATERIAL_ITEM_SNAPSHOT`
- `MATERIAL_SET_SNAPSHOT`

`RemotePreviewStatus`：

- `DRAFT`
- `READY_FOR_CONFIRMATION`
- `CONFIRMED`
- `INVALIDATED`
- `CONSUMED`

`OfficialBasisSourceLocator`：

- `entryPath`：官方依据来自 ZIP 叶子时必填，否则为 `null`；
- `page`：PDF/DOCX 分页定位可用时填写，否则为 `null`；
- `sheet`、`cellRange`：XLSX 定位可用时填写，否则为 `null`；
- `block`：解析器稳定块 ID 可用时填写，否则为 `null`；
- `entryDigest`：ZIP 叶子时必填，否则为 `null`。

官方提取候选必须至少有 `basisId` 和一种可核对定位；无法可靠定位的模型文字不能作为 `OFFICIAL_EXTRACTED` 候选。

## 3. 修订和输入指纹

案例至少维护以下单调递增修订：

| 修订 | 何时增加 | 影响 |
| --- | --- | --- |
| `basisRevision` | 附加、移除、重新分类或替换官方依据 | 当前清单变 `STALE_BASIS`，全部当前报告 `OUTDATED` |
| `checklistRevision` | 候选项确认、新增、改写、启停、适用性、数量或命名规则变化 | 未发布草稿变化；发布新快照后旧报告 `OUTDATED` |
| `materialSetRevision` | 顶层文件/ZIP 叶子加入、移除、替换，或源字节摘要变化 | 旧上传完毕确认失效，当前报告 `OUTDATED` |
| `assignmentRevision` | 建议、确认、拒绝或撤销材料分配 | 当前报告 `OUTDATED`，不自动使“上传完毕”失效 |
| `nameRevision` | 任一逻辑 `submissionFileName` 变化 | 当前报告 `OUTDATED`，不修改原件，也不自动使“上传完毕”失效 |

一个报告的 `inputFingerprint` 必须由下列规范字段计算 SHA-256：

- `contractVersion`；
- `caseId`；
- `basisRevision` 及有序依据摘要；
- `checklistSnapshotId` 及快照摘要；
- `materialSetRevision` 及有序材料摘要；
- `assignmentRevision` 及有序已确认分配；
- `nameRevision` 及有序逻辑文件名；
- `uploadConfirmationId`；
- 审查引擎规则版本。

有序列表按稳定 ID 的 Unicode 码点顺序排列，字段名和枚举使用本契约原值，JSON 采用 UTF-8、无无意义空白的规范序列化。实现任务必须交付跨进程稳定性测试，不能使用语言默认对象字符串或数据库返回顺序。

## 4. 状态机

### 4.1 官方依据与清单

```text
OfficialBasis: ATTACHED -> READY
                       -> CANNOT_CONFIRM
              READY/CANNOT_CONFIRM -> DETACHED

Checklist: CANDIDATE_DRAFT -> CONFIRMED -> SUPERSEDED
                                |  |
             basis change ------+  +---- new confirmed snapshot
                                v
                           STALE_BASIS
```

规则：

- `CANNOT_CONFIRM` 的依据诊断保留；用户修复或替换后以新依据修订重新处理，不覆写历史事实。
- `CANDIDATE_DRAFT` 不能用于正式审查。
- 当前附着的官方依据存在 `CANNOT_CONFIRM` 时，可以保存其他来源产生的候选草稿，但不能发布正式清单；用户需替换或显式移除问题依据后重新提取/确认。
- 当前依据集变化时，所有基于旧 `basisRevision` 的 `CONFIRMED` 清单都进入 `STALE_BASIS`；新正式审查被拒绝，直到用户基于新依据修订确认新快照。
- 发布新 `CONFIRMED` 快照后，同一案例上一份当前确认快照变 `SUPERSEDED`；历史报告仍引用原快照。

### 4.2 上传确认

上传确认不是可变状态，而是不可变事件，绑定：

- `caseId`；
- `checklistSnapshotId`；
- `materialSetRevision`；
- `confirmedBy`；
- `confirmedAt`。

材料集合变化或清单快照变化后，旧事件仍保留但不满足新审查前置条件。逻辑改名和材料分配变化不改变上传字节集合，因此不使该事件失效；它们仍会使旧报告过期并触发重审。

### 4.3 后台任务

允许转换：

```text
QUEUED -> RUNNING -> SUCCEEDED
  |          |  \-> FAILED
  |          \----> CANCEL_REQUESTED -> CANCELLED
  \---------------> CANCEL_REQUESTED -> CANCELLED
```

补充规则：

- `SUCCEEDED`、`FAILED`、`CANCELLED` 是终态。
- 对终态取消必须幂等返回原任务，不倒退状态。
- Worker 只有持有有效租约才能推进 checkpoint；租约过期后其他 Worker 可从最后已提交 checkpoint 恢复。
- `CANCEL_REQUESTED` 后不得开始新的外部模型调用或新的材料单元；当前不可中断操作结束后进入 `CANCELLED`。
- 只有同一事务成功发布正式清单/报告并把任务置为 `SUCCEEDED`，结果才可成为当前资源。
- `FAILED`、`CANCELLED` 不发布半成品报告；诊断和 checkpoint 可以保留但不进入用户正式结果。

### 4.4 报告新鲜度

```text
CURRENT -> OUTDATED
```

`OUTDATED` 不可逆。重审创建新报告，不把旧报告改回 `CURRENT`。同一案例最多一个 `CURRENT` 报告；新报告原子发布时把旧当前报告置为 `OUTDATED`。

导致过期的事件：

- `basisRevision`、正式清单快照、`materialSetRevision`、`assignmentRevision` 或 `nameRevision` 变化；
- 审查引擎规则版本变化；
- 证据引用的源文件摘要与快照不一致或源文件不可用。

过期动作应与业务变更在同一事务中完成，并清空案例的 `currentReportId`；后台兜底扫描只能修复，不得作为唯一正确性机制。

## 5. 正式审查算法

### 5.1 前置门

正式审查提交前必须依次验证：

1. 案例存在且归认证用户所有；
2. 依据集没有未处理的修订漂移；
3. `checklistSnapshotId` 为该案例当前 `CONFIRMED` 快照；
4. 所有启用的条件项都不是 `NEEDS_USER_DECISION`；
5. 存在绑定当前清单快照和当前 `materialSetRevision` 的上传确认；
6. 没有同一幂等请求的载荷冲突；
7. 增量审查的 `baseReportId` 是本案例报告，且其不受影响输入可以被校验复用。

任一失败都拒绝任务提交，不生成报告，也不生成 `MISSING`。

### 5.2 材料可用性

正式材料单元只有满足以下条件才计数：

- `MaterialStatus=AVAILABLE`；
- 文件类型是 PDF、DOCX、XLSX、JPG 或 PNG；
- 对 ZIP 来源，叶子条目已通过容器安全校验；
- 存在对应项目的 `USER_CONFIRMED` 分配；
- 源摘要与当前快照相同。

无法可靠处理的材料不计为可用，但它必须产生 `CANNOT_CONFIRM`。只要当前提交材料集合存在任一未决 `CANNOT_CONFIRM`，整个报告就不生成 `MISSING`；系统不得猜测该输入原本对应哪个清单项目。

### 5.3 发现生成

先把当前官方/提交输入诊断投影为 `CANNOT_CONFIRM` 发现。对每个启用且 `APPLIES` 的项目：

1. 收集已确认分配的可用材料；
2. 当前提交材料集合没有未决 `CANNOT_CONFIRM`，且可用数 `< minimumCount` 时，输出一个 `MISSING`；
3. 对每个可用材料，以其当前 `submissionFileName` 执行全局规则和项目文件名规则；每个不合规材料输出 `FILE_NAME_NONCOMPLIANT`。

未决 `CANNOT_CONFIRM` 只抑制 `MISSING`，不抑制其他可可靠执行的逻辑文件名检查。

`DOES_NOT_APPLY` 项不做缺失计数。`NEEDS_USER_DECISION` 不允许进入正式审查。

### 5.4 证据要求

| 发现 | 必需规则证据 | 必需材料证据 |
| --- | --- | --- |
| `MISSING` | 当前正式清单项目、来源谱系；可定位官方片段存在时同时引用 | `MATERIAL_SET_SNAPSHOT`，含上传确认、材料列表、分配和计数摘要 |
| `FILE_NAME_NONCOMPLIANT` | 项目级规则或 `GLOBAL_M0_POLICY` 的具体规则 ID | `MATERIAL_ITEM_SNAPSHOT`，含 `materialId`、逻辑名、类型、摘要和 ZIP 条目定位（适用时） |
| `CANNOT_CONFIRM` | 适用的输入/容器处理规则；不要求伪造业务缺失规则 | 对应材料或依据快照、失败阶段和稳定原因码 |

规则证据和材料证据必须是结构化引用，不能只写一段模型解释。证据展示不得泄漏 MinIO 凭据、预签名 URL、对象存储密钥或远程模型密钥。

### 5.5 总体结论

当前报告只有同时满足以下条件才为 `M0_READY`：

- `freshness=CURRENT`；
- `MISSING=0`；
- `FILE_NAME_NONCOMPLIANT=0`；
- `CANNOT_CONFIRM=0`；
- 忽略决定覆盖的发现数为 0；
- 报告引擎验证全部不变量通过。

其余情况一律 `M0_NOT_READY`。忽略只影响 API/UI 的有效投影或后续提醒，不改变不可变报告、不改变原发现类型、不从三类发现计数中删除，也不能把报告提升为 `M0_READY`。

## 6. 单文件增量重审

`INCREMENTAL_REVIEW` 不是部分事实报告。它必须：

1. 引用 `baseReportId`；
2. 只重新读取指定 `changedMaterialIds` 及受其分配影响的项目；
3. 校验其他输入与基础报告保存的摘要相同；
4. 重新计算受影响项目的缺失计数和命名发现；
5. 复制复核后仍有效的未受影响发现，并保留其原证据引用；
6. 生成新的完整 `inputFingerprint` 和不可变 `reportId`；
7. 原子地发布新当前报告并将基础报告标为 `OUTDATED`。

若依据、清单快照、引擎规则或未声明材料发生变化，增量任务必须以 `REVIEW_M0_INCREMENTAL_BASE_MISMATCH` 失败，调用方应提交完整 `FORMAL_REVIEW`；不能静默漏检。

逻辑文件名变化可以只重审对应材料的命名，但如果该材料状态、来源摘要或分配同时变化，就必须扩大到所有受影响项目的齐全性重算。

## 7. 不变量

下列为 P0 硬不变量：

1. 不修改任何原始文件字节、原始文件名或 `objectKey`。
2. 不存在上传完毕确认时，`MISSING` 数恒为 0，正式审查任务不得启动。
3. 候选清单永远不能作为正式审查规则。
4. 每个 `MISSING` 和 `FILE_NAME_NONCOMPLIANT` 同时有规则证据和材料/材料集合证据。
5. `CANNOT_CONFIRM`、`FAILED` 和 `MISSING` 三类语义不可互换。
6. 任何模型输出只能成为候选，不能直接成为正式清单或确定性结论。
7. 每次远程发送的载荷摘要必须与用户最后确认的预览摘要一致。
8. `IGNORE` 不删除发现、不改变证据、不使总体结论为 `M0_READY`。
9. 输入修订或引擎版本变化后旧报告在事务提交时即为 `OUTDATED`。
10. 重试相同后台请求不创建重复任务、清单快照、报告或远程发送。
11. `FAILED` 或 `CANCELLED` 任务不发布正式结果。
12. ZIP 不能嵌套；ZIP 叶子只能是五类正式非容器格式。
13. 单文件下载只按 `materialId` 定位并使用逻辑名呈现，不以用户提供路径读取文件系统。
14. 所有案例资源按认证用户隔离；异主资源对调用者表现为不存在。
15. 报告和证据是不可变审计事实；修复通过新报告表达。
