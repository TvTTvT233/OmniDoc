# Review M0：API、存储与错误冻结

## 1. 组件所有权

### 1.1 服务边界

| 能力/事实 | 所有者 | Review M0 规则 |
| --- | --- | --- |
| 原始文件字节、`fileId`、原始文件名、MinIO bucket/`objectKey` | `file-service` | 只读引用；不得在 Review 表复制对象存储凭据，不得改写 |
| 通用文档解析能力 | `document-service` | 可通过适配口复用；M0 不建立平行解析平台 |
| Review M0 案例、清单、任务、报告、证据和逻辑文件名 | `ai-service` 的独立 `review.m0` 边界 | 使用独立包、表和迁移；不把状态塞入 Conversation/Memory 表 |
| Review M0 HTTP 资源 | `ai-service` 的独立 Controller | 根路径固定为 `/api/review-m0`；不修改旧 API 响应 |
| 候选提取执行 | Review M0 编排器 | 本地优先；远程时必须验证一次性预览授权 |
| 正式审查 | Review M0 确定性引擎 | 不调用远程模型或通用 Agent 推导缺失/命名结论 |
| 原始用户身份 | 认证上下文 | 请求 JSON 不接收授权用 `userId` |

冻结的 Review 实现不能修改 `AgentExecutionService` 或 `AgentToolRegistry`。若后续需要把 Review 作为通用 Agent 工具暴露，必须由独立集成任务通过新适配器完成；M0 正式审查的确定性引擎仍是唯一结论所有者。

### 1.2 文件适配口

Review M0 需要供应商无关的内部只读口，语义冻结如下；Java 类名可以按现有项目风格落地，但字段不可减少：

```text
getOwnedFile(userId, fileId) -> FileArtifactSnapshot
openFile(userId, fileId) -> byte stream
openZipEntry(userId, fileId, normalizedEntryPath, expectedEntryDigest) -> byte stream
```

`FileArtifactSnapshot`：

```json
{
  "fileId": "file_01",
  "originalFileName": "申请表.pdf",
  "declaredContentType": "application/pdf",
  "sizeBytes": 102400,
  "contentDigest": "sha256-lower-hex",
  "storageVersion": "opaque-version",
  "available": true
}
```

Review API 和 Review 数据库均不返回或存储 MinIO 访问密钥、预签名 URL。`bucketName/objectKey` 如为现有文件服务内部定位所必需，仍只属于文件服务，不进入 Review 公共契约。

现有文件服务的所有者校验、稳定内容摘要、范围/流式读取和 ZIP 叶子读取能力是否可直接复用，尚未运行验证；M0-01 必须只读确认。缺能力时由文件适配任务在文件服务所有者批准的范围内补齐，不能由 Review 绕过鉴权直接读取数据库或 MinIO。

## 2. HTTP 通用规则

- 根路径：`/api/review-m0`。
- JSON：UTF-8，字段使用 lowerCamelCase，枚举使用本契约大写值。
- 时间：ISO-8601 UTC 字符串，例如 `2026-08-13T08:30:00.123Z`。
- 成功响应直接返回资源，不使用旧 `Result` 包装。
- 创建后台任务返回 HTTP `202`；同步创建资源返回 `201`；幂等重放返回原状态码和原资源。
- 所有写请求要求 `Idempotency-Key` Header；`PATCH` 还要求请求体 `expectedVersion`。
- `Idempotency-Key` 在 `owner + method + canonicalPath` 域内唯一。相同键和相同规范载荷返回原结果；相同键不同载荷返回 `REVIEW_M0_IDEMPOTENCY_CONFLICT`。
- 列表的稳定分页游标属于实现任务；本契约首版只冻结案例内资源读取，不冻结案例列表产品体验。
- 客户端传入的未知 JSON 字段返回 `REVIEW_M0_REQUEST_INVALID`，不得静默忽略可能拼错的关键字段。
- 可选字段在响应中明确为 `null`；不得用空字符串代替未知 ID、时间或原因。

错误载体：

```json
{
  "errorCode": "REVIEW_M0_UPLOAD_NOT_CONFIRMED",
  "message": "A current upload confirmation is required",
  "requestId": "req_01",
  "details": {
    "caseId": "case_01",
    "requiredMaterialSetRevision": 4
  }
}
```

`message` 是展示辅助，不是稳定机器字段。`details` 只能放安全结构化定位，不得放材料正文、模型提示、凭据、对象键或堆栈。

## 3. 案例和官方依据 API

### 3.1 创建案例

`POST /api/review-m0/cases`

请求：

```json
{
  "title": "2026 国家奖学金材料",
  "processingMode": "LOCAL_ONLY"
}
```

响应 `201`：

```json
{
  "caseId": "case_01",
  "title": "2026 国家奖学金材料",
  "processingMode": "LOCAL_ONLY",
  "basisRevision": 0,
  "checklistRevision": 0,
  "materialSetRevision": 0,
  "assignmentRevision": 0,
  "nameRevision": 0,
  "version": 0,
  "currentChecklistSnapshotId": null,
  "currentReportId": null,
  "createdAt": "2026-08-13T08:30:00.123Z",
  "updatedAt": "2026-08-13T08:30:00.123Z"
}
```

`processingMode` 只允许 `LOCAL_ONLY` 或 `REMOTE_MODEL`; 默认请求必须显式发送 `LOCAL_ONLY`，前端不得用远程作为隐式默认。

案例上的 `processingMode` 是候选提取的默认偏好，不构成发送授权。为避免未经确认的模式切换 API，本契约要求提取请求的 `processingMode` 与案例值一致；使用远程能力需要新建显式 `REMOTE_MODEL` 案例，并仍须携带匹配的一次性 `consentId`。已有案例未来是否可切换模式属于待确认产品事实。

`GET /api/review-m0/cases/{caseId}` 返回同一资源。

### 3.2 附加和移除官方依据

`POST /api/review-m0/cases/{caseId}/official-bases`

请求：

```json
{
  "fileId": "file_notice_01",
  "role": "APPLICATION_NOTICE"
}
```

响应 `201`：

```json
{
  "basisId": "basis_01",
  "fileId": "file_notice_01",
  "role": "APPLICATION_NOTICE",
  "status": "ATTACHED",
  "originalFileNameSnapshot": "申报通知.pdf",
  "fileDigest": "sha256-lower-hex",
  "basisRevision": 1,
  "version": 0,
  "cannotConfirmReason": null,
  "diagnostics": [],
  "createdAt": "2026-08-13T08:31:00.000Z"
}
```

顶层非白名单官方依据返回 HTTP `415`。ZIP 官方依据的 `role` 作用于全部合法叶子；候选的 `sourceLocator` 必须包含安全规范化的 `entryPath` 和条目摘要。任一叶子违反容器规则时，依据记录保留并进入 `CANNOT_CONFIRM`，`diagnostics` 列出每个问题；在替换或移除该依据前不能发布正式清单。

`DELETE /api/review-m0/cases/{caseId}/official-bases/{basisId}` 请求体：

```json
{
  "expectedVersion": 0
}
```

响应 `200` 返回 `status=DETACHED` 的依据资源。物理记录和历史证据不删除。

`GET /api/review-m0/cases/{caseId}/official-bases` 返回：

```json
{
  "items": [],
  "basisRevision": 1
}
```

`PATCH /api/review-m0/cases/{caseId}/official-bases/{basisId}` 用于用户重新分类：

```json
{
  "expectedVersion": 0,
  "role": "FILLING_GUIDE"
}
```

响应 `200` 返回依据资源和新 `basisRevision`；当前正式清单进入 `STALE_BASIS`，当前报告进入 `OUTDATED`。

## 4. 脱敏预览和候选提取 API

### 4.1 生成预览

`POST /api/review-m0/cases/{caseId}/remote-previews`

请求：

```json
{
  "operation": "CHECKLIST_EXTRACTION",
  "basisRevision": 1
}
```

响应 `201`：

```json
{
  "previewId": "preview_01",
  "operation": "CHECKLIST_EXTRACTION",
  "status": "DRAFT",
  "basisRevision": 1,
  "segments": [
    {
      "segmentId": "seg_01",
      "basisId": "basis_01",
      "sourceLocator": {
        "entryPath": null,
        "page": 2,
        "sheet": null,
        "cellRange": null,
        "block": "b17",
        "entryDigest": null
      },
      "included": true,
      "redactedText": "需提交[已脱敏]申请表一份"
    }
  ],
  "payloadDigest": "sha256-lower-hex",
  "version": 0,
  "createdAt": "2026-08-13T08:32:00.000Z"
}
```

`sourceLocator` 只用于用户核对，不发送到远程，除非它本身出现在 `redactedText` 且用户保留。真正远程载荷只由 `included=true` 的 `redactedText` 按 `segmentId` 稳定顺序规范化得到。

### 4.2 调整并确认预览

`PATCH /api/review-m0/cases/{caseId}/remote-previews/{previewId}`

```json
{
  "expectedVersion": 0,
  "segments": [
    {
      "segmentId": "seg_01",
      "included": true,
      "redactedText": "需提交申请表一份"
    }
  ]
}
```

响应 `200` 返回新 `payloadDigest`、`version` 和 `status=READY_FOR_CONFIRMATION`。任何修改使旧授权失效。

`POST /api/review-m0/cases/{caseId}/remote-previews/{previewId}/confirm`

```json
{
  "expectedVersion": 1,
  "acknowledgedPayloadDigest": "sha256-lower-hex"
}
```

响应 `201`：

```json
{
  "consentId": "consent_01",
  "previewId": "preview_01",
  "payloadDigest": "sha256-lower-hex",
  "basisRevision": 1,
  "operation": "CHECKLIST_EXTRACTION",
  "consumedByJobId": null,
  "confirmedAt": "2026-08-13T08:34:00.000Z"
}
```

### 4.3 提交候选提取

`POST /api/review-m0/cases/{caseId}/checklist-extractions`

本地请求：

```json
{
  "basisRevision": 1,
  "processingMode": "LOCAL_ONLY",
  "consentId": null
}
```

远程请求必须使用 `REMOTE_MODEL` 和未消费的当前 `consentId`。响应 `202` 返回第 8 节的 Job 资源。远程响应只更新候选草稿；任务成功也不发布正式清单。

## 5. 候选清单 API

### 5.1 读取草稿

`GET /api/review-m0/cases/{caseId}/checklist-draft`

```json
{
  "checklistRevisionId": "clrev_01",
  "lifecycle": "CANDIDATE_DRAFT",
  "basisRevision": 1,
  "revision": 3,
  "items": [
    {
      "requirementId": "reqmt_01",
      "displayName": "国家奖学金申请表",
      "enabled": true,
      "applicability": "APPLIES",
      "minimumCount": 1,
      "fileNameRule": {
        "kind": "EXACT",
        "pattern": "国家奖学金申请表.pdf",
        "caseSensitive": true
      },
      "origin": "OFFICIAL_EXTRACTED",
      "authorship": "EXTRACTED_TEXT",
      "itemVersion": 0,
      "sourceRefs": [
        {
          "basisId": "basis_01",
          "sourceLocator": {
            "entryPath": null,
            "page": 2,
            "sheet": null,
            "cellRange": null,
            "block": "b17",
            "entryDigest": null
          },
          "quotedText": "需提交国家奖学金申请表一份"
        }
      ]
    }
  ]
}
```

`quotedText` 必须是短定位摘录，不能存整份文件；无法可靠定位时不能凭模型补造，候选需要用户改写/新增后才能确认。

### 5.2 用户新增与改写/启停

`POST /api/review-m0/cases/{caseId}/checklist-draft/requirements`

```json
{
  "displayName": "学院要求的汇总封面",
  "enabled": true,
  "applicability": "APPLIES",
  "minimumCount": 1,
  "fileNameRule": {"kind": "NONE", "pattern": null, "caseSensitive": null}
}
```

服务端强制 `origin=USER_ADDED`、`authorship=USER_REWRITTEN`。

`PATCH /api/review-m0/cases/{caseId}/checklist-draft/requirements/{requirementId}`

```json
{
  "expectedVersion": 0,
  "displayName": "国家奖学金申请表（PDF）",
  "enabled": true,
  "applicability": "APPLIES",
  "minimumCount": 1,
  "fileNameRule": {
    "kind": "EXACT",
    "pattern": "国家奖学金申请表.pdf",
    "caseSensitive": true
  }
}
```

只要用户改变提取文字或结构化规则，服务端把 `authorship` 置为 `USER_REWRITTEN`，但保留 `origin` 和 `sourceRefs`。停用使用同一 PATCH 的 `enabled=false`；不提供删除端点。

### 5.3 发布正式清单

`POST /api/review-m0/cases/{caseId}/checklist-snapshots`

```json
{
  "expectedChecklistRevision": 5,
  "acknowledgedRequirementIds": ["reqmt_01", "reqmt_02"]
}
```

`acknowledgedRequirementIds` 必须与当前全部启用项目的稳定 ID 集合精确相等；有 `NEEDS_USER_DECISION` 时拒绝。当前附着依据存在 `status=CANNOT_CONFIRM` 时同样拒绝发布，返回 `REVIEW_M0_BASIS_CANNOT_CONFIRM`。这不删除已编辑候选；用户替换或移除问题依据后需基于新 `basisRevision` 重新确认。响应 `201`：

```json
{
  "checklistSnapshotId": "clsnap_01",
  "lifecycle": "CONFIRMED",
  "basisRevision": 1,
  "checklistRevision": 5,
  "snapshotDigest": "sha256-lower-hex",
  "itemCount": 2,
  "confirmedAt": "2026-08-13T08:40:00.000Z"
}
```

`GET /api/review-m0/cases/{caseId}/checklist-snapshots/{checklistSnapshotId}` 返回不可变快照及与草稿相同结构的 `items`。

## 6. 提交材料、分配和上传确认 API

### 6.1 附加源文件

`POST /api/review-m0/cases/{caseId}/submission-sources`

```json
{
  "fileId": "file_submission_01",
  "submissionFileName": "国家奖学金申请表.pdf"
}
```

普通文件使用请求逻辑名；ZIP 的请求 `submissionFileName` 必须为 `null`，服务端为每个合法叶子条目从安全规范化后的叶子名初始化逻辑名。响应 `201`：

```json
{
  "sourceId": "source_01",
  "fileId": "file_submission_01",
  "originalFileNameSnapshot": "upload_9281.pdf",
  "fileDigest": "sha256-lower-hex",
  "formalFileType": "PDF",
  "status": "ATTACHED",
  "materialSetRevision": 1,
  "materials": [
    {
      "materialId": "mat_01",
      "kind": "TOP_LEVEL_FILE",
      "submissionFileName": "国家奖学金申请表.pdf",
      "formalFileType": "PDF",
      "status": "AVAILABLE",
      "entryLocator": null,
      "contentDigest": "sha256-lower-hex",
      "version": 0,
      "cannotConfirmReason": null
    }
  ],
  "diagnostics": []
}
```

ZIP 的非法条目通过 `diagnostics` 返回 `FindingType=CANNOT_CONFIRM` 结构，不以 `MISSING` 表示。顶层非白名单扩展直接返回 HTTP `415`。

`DELETE /api/review-m0/cases/{caseId}/submission-sources/{sourceId}` 请求体含 `expectedVersion`，响应 `200` 返回 `status=REMOVED` 和新 `materialSetRevision`；材料、分配和历史证据软删除保留。

`GET /api/review-m0/cases/{caseId}/submission-sources` 返回当前与已移除源的摘要、每个源的材料和诊断：

```json
{
  "materialSetRevision": 1,
  "items": []
}
```

`GET /api/review-m0/cases/{caseId}/materials` 返回当前材料平铺列表：

```json
{
  "materialSetRevision": 1,
  "nameRevision": 0,
  "items": []
}
```

### 6.2 修改逻辑名

`PATCH /api/review-m0/cases/{caseId}/materials/{materialId}`

```json
{
  "expectedVersion": 0,
  "submissionFileName": "国家奖学金申请表.pdf"
}
```

响应 `200` 返回材料资源和新 `nameRevision`。该操作不调用文件服务写接口。

### 6.3 分配

`PUT /api/review-m0/cases/{caseId}/assignments`

```json
{
  "expectedAssignmentRevision": 2,
  "assignments": [
    {
      "materialId": "mat_01",
      "requirementId": "reqmt_01",
      "status": "USER_CONFIRMED"
    }
  ]
}
```

请求是当前分配集合的全量替换；省略的旧关系变 `REJECTED`，审计历史保留。响应：

```json
{
  "assignmentRevision": 3,
  "assignments": []
}
```

`GET /api/review-m0/cases/{caseId}/assignments` 返回同一结构。

### 6.4 确认上传完毕

`POST /api/review-m0/cases/{caseId}/upload-confirmations`

```json
{
  "checklistSnapshotId": "clsnap_01",
  "materialSetRevision": 1,
  "acknowledgedUploadComplete": true
}
```

响应 `201`：

```json
{
  "uploadConfirmationId": "uploadcfm_01",
  "checklistSnapshotId": "clsnap_01",
  "materialSetRevision": 1,
  "confirmedAt": "2026-08-13T08:45:00.000Z"
}
```

`acknowledgedUploadComplete` 只能为 `true`；客户端不能通过发送 `false` 创建模糊事件。

### 6.5 单份副本下载

`GET /api/review-m0/cases/{caseId}/materials/{materialId}/download`

- 成功：HTTP `200`，字节流，`Content-Disposition: attachment` 同时提供安全 ASCII fallback 和 RFC 5987 UTF-8 `filename*`，其值来自当前 `submissionFileName`。
- 普通文件返回源文件字节；ZIP 材料只返回对应叶子条目字节，不返回整个 ZIP。
- 下载前验证案例所有者、材料当前/历史可访问性、源摘要和 ZIP 条目摘要。
- `submissionFileName` 只能用于响应头，不用于拼接服务端路径。

## 7. 正式审查、报告和决定 API

### 7.1 提交完整或增量审查

`POST /api/review-m0/cases/{caseId}/reviews`

完整：

```json
{
  "mode": "FULL",
  "checklistSnapshotId": "clsnap_01",
  "uploadConfirmationId": "uploadcfm_01",
  "baseReportId": null,
  "changedMaterialIds": []
}
```

增量：

```json
{
  "mode": "INCREMENTAL",
  "checklistSnapshotId": "clsnap_01",
  "uploadConfirmationId": "uploadcfm_01",
  "baseReportId": "report_01",
  "changedMaterialIds": ["mat_01"]
}
```

响应 `202` 返回 Job。`FULL` 的 `baseReportId` 必须为 `null` 且 `changedMaterialIds` 为空；`INCREMENTAL` 两者都必填且材料集合非空。

### 7.2 报告

`GET /api/review-m0/cases/{caseId}/reports/current` 返回当前报告；不存在时返回 `404 REVIEW_M0_RESOURCE_NOT_FOUND`，且 `details.resourceType=REPORT`。

`GET /api/review-m0/cases/{caseId}/reports/{reportId}` 可读取 `OUTDATED` 历史报告：

```json
{
  "reportId": "report_01",
  "caseId": "case_01",
  "contractVersion": "REVIEW_M0_CONTRACT_V1",
  "engineVersion": "m0-engine-v1",
  "freshness": "CURRENT",
  "assessment": "M0_NOT_READY",
  "inputFingerprint": "sha256-lower-hex",
  "checklistSnapshotId": "clsnap_01",
  "uploadConfirmationId": "uploadcfm_01",
  "basisRevision": 1,
  "materialSetRevision": 1,
  "assignmentRevision": 3,
  "nameRevision": 0,
  "baseReportId": null,
  "summary": {
    "missingCount": 1,
    "fileNameNoncompliantCount": 0,
    "cannotConfirmCount": 0,
    "ignoredCount": 0
  },
  "findings": [
    {
      "findingId": "finding_01",
      "type": "MISSING",
      "cannotConfirmReason": null,
      "requirementId": "reqmt_02",
      "materialId": null,
      "message": "缺少已确认清单要求的材料",
      "ignored": false,
      "ruleEvidence": [
        {
          "kind": "OFFICIAL_BASIS_LOCATION",
          "refId": "evidence_rule_01",
          "basisId": "basis_01",
          "sourceLocator": {
            "entryPath": null,
            "page": 2,
            "sheet": null,
            "cellRange": null,
            "block": "b18",
            "entryDigest": null
          },
          "requirementId": "reqmt_02"
        }
      ],
      "materialEvidence": [
        {
          "kind": "MATERIAL_SET_SNAPSHOT",
          "refId": "evidence_set_01",
          "uploadConfirmationId": "uploadcfm_01",
          "materialSetRevision": 1,
          "expectedCount": 1,
          "confirmedAvailableCount": 0,
          "materialIds": []
        }
      ]
    }
  ],
  "createdAt": "2026-08-13T08:47:00.000Z"
}
```

报告中的 `message` 可本地化；机器判断只读取类型、原因、证据和计数。

报告历史列表的分页与保留期尚未确认，本契约不冻结 `GET .../reports` 集合端点。客户端保存 `reportId` 后可以读取特定历史报告；在 `P-01` 确认前，实现任务不得自行新增一个无界或静默截断的历史列表接口。

### 7.3 忽略决定

`POST /api/review-m0/cases/{caseId}/reports/{reportId}/findings/{findingId}/decisions`

```json
{
  "decision": "IGNORE",
  "reason": "用户暂不处理"
}
```

响应 `201` 返回不可变 `IGNORE` 决定事件，并附带由“不可变报告 + 决定事件”计算的当前 `effectiveSummary/effectiveAssessment` 投影；不得更新报告事实行。决定只允许施加于 `CURRENT` 报告；历史报告已有决定仍可读。重复忽略同一发现幂等返回原决定。`IGNORE` 后发现仍在 `findings` 中且 `effectiveAssessment=M0_NOT_READY`。撤销忽略尚待产品确认，本契约不提供对应枚举或端点。`GET` 报告时，顶层 `assessment` 是不可变生成结论；若存在决定，响应另加 `effectiveAssessment`，客户端不得用前者误显为 Ready。

## 8. 后台任务 API

`GET /api/review-m0/cases/{caseId}/jobs/{jobId}`：

```json
{
  "jobId": "job_01",
  "type": "FORMAL_REVIEW",
  "status": "RUNNING",
  "progress": {"completedUnits": 2, "totalUnits": 5},
  "attempt": 1,
  "resultRef": null,
  "error": null,
  "createdAt": "2026-08-13T08:46:00.000Z",
  "startedAt": "2026-08-13T08:46:01.000Z",
  "finishedAt": null
}
```

失败时：

```json
{
  "jobId": "job_01",
  "type": "CHECKLIST_EXTRACTION",
  "status": "FAILED",
  "progress": {"completedUnits": 1, "totalUnits": 3},
  "attempt": 3,
  "resultRef": null,
  "error": {
    "errorCode": "REVIEW_M0_REMOTE_MODEL_FAILED",
    "retryable": true,
    "message": "Remote extraction failed"
  },
  "createdAt": "2026-08-13T08:32:00.000Z",
  "startedAt": "2026-08-13T08:32:01.000Z",
  "finishedAt": "2026-08-13T08:33:00.000Z"
}
```

`POST /api/review-m0/cases/{caseId}/jobs/{jobId}/cancel` 无请求体；返回 `202` 的最新 Job。终态重放返回 `200` 原 Job。

Job 错误不能出现在报告 `findings` 中。

## 9. MySQL 表契约

### 9.1 通用约束

- 事实主库：MySQL 8 / Spring JDBC；Review 状态不以 Redis 为事实主库。
- 时间列：UTC `DATETIME(3)`；应用层输出 ISO-8601 UTC。
- ID：`VARCHAR(64)`；`user_id VARCHAR(128)`；摘要：`CHAR(64)` 小写 SHA-256 hex。
- 结构化不可变快照使用 `LONGTEXT` 保存规范 JSON，并保存独立 digest；读取时校验 JSON、版本和 digest。
- 所有可变聚合含 `version BIGINT NOT NULL`，使用 CAS 更新。
- 逻辑文件名列使用 `VARCHAR(1024)` 作为技术存储包络，不代表产品已确认 1024 字符上限。
- 外键应阻止孤儿，但用户操作默认软删除/状态转换；保留期确定前不提供级联物理删除。
- 迁移放在 Review M0 自有迁移目录/执行器，版本前缀与 Memory 迁移隔离；具体迁移框架由基线任务核对，不得把 Review DDL 混入 Memory V001/V002。

### 9.2 表与字段

`review_m0_case`

```text
case_id PK, user_id, title, processing_mode,
basis_revision, checklist_revision, material_set_revision,
assignment_revision, name_revision, version,
current_checklist_snapshot_id NULL, current_report_id NULL,
created_at, updated_at
```

索引：`(user_id, updated_at, case_id)`；当前清单/报告外键采用延后补充或可空外键，避免创建循环。

`review_m0_official_basis`

```text
basis_id PK, case_id FK, file_id, role, status,
original_file_name_snapshot, declared_content_type NULL,
file_size_bytes, file_digest, storage_version NULL,
cannot_confirm_reason NULL, failure_stage NULL,
attached_basis_revision, detached_basis_revision NULL,
version, created_at, updated_at
```

唯一：`(case_id, basis_id)`；索引：`(case_id, status, role)`。不对 `file_id` 全局唯一，允许同一用户在不同案例引用。

`review_m0_checklist_revision`

```text
checklist_revision_id PK, case_id FK, lifecycle, basis_revision,
revision_number, draft_digest, extraction_job_id NULL,
created_by, created_at
```

唯一：`(case_id, revision_number)`。

`review_m0_requirement`

```text
requirement_id PK, case_id FK, origin, created_at
```

`review_m0_requirement_revision`

```text
requirement_revision_id PK, requirement_id FK,
checklist_revision_id FK, display_name, enabled, applicability,
minimum_count, file_name_rule_kind, file_name_rule_pattern NULL,
file_name_rule_case_sensitive NULL,
authorship, source_refs_json, item_version, created_at
```

唯一：`(requirement_id, checklist_revision_id)`；`minimum_count >= 1`。

`review_m0_checklist_snapshot`

```text
checklist_snapshot_id PK, case_id FK, checklist_revision_id FK,
basis_revision, checklist_revision, snapshot_json, snapshot_digest,
lifecycle, confirmed_by, confirmed_at, superseded_at NULL
```

唯一：`(case_id, checklist_revision)`；索引：`(case_id, lifecycle)`。

`review_m0_submission_source`

```text
source_id PK, case_id FK, file_id,
original_file_name_snapshot, declared_content_type NULL,
formal_file_type, status, file_size_bytes, file_digest,
storage_version NULL, attached_material_set_revision,
removed_material_set_revision NULL, version, created_at, updated_at
```

`review_m0_material`

```text
material_id PK, case_id FK, source_id FK, kind,
normalized_entry_path NULL, original_entry_name NULL,
entry_index NULL, content_digest, formal_file_type,
submission_file_name, normalized_submission_file_name,
status, cannot_confirm_reason NULL, failure_stage NULL,
material_set_revision, name_revision, version, created_at, updated_at
```

唯一：普通文件 `(source_id, kind)`；ZIP 叶子 `(source_id, normalized_entry_path)`。`normalized_entry_path` 只作安全定位，不对外作为下载路径。

`review_m0_material_assignment`

```text
assignment_id PK, case_id FK, material_id FK, requirement_id FK,
status, assignment_revision, decided_by NULL, created_at
```

同一 `(case_id, material_id, requirement_id, assignment_revision)` 唯一；历史保留。

`review_m0_upload_confirmation`

```text
upload_confirmation_id PK, case_id FK, checklist_snapshot_id FK,
material_set_revision, material_set_digest,
confirmed_by, confirmed_at
```

唯一：`(case_id, checklist_snapshot_id, material_set_revision, material_set_digest)`。

`review_m0_remote_preview`

```text
preview_id PK, case_id FK, operation, status, basis_revision,
segments_json, payload_digest, version, created_by,
created_at, updated_at
```

`review_m0_remote_consent`

```text
consent_id PK, preview_id FK, case_id FK, operation,
basis_revision, payload_digest, confirmed_by, confirmed_at,
consumed_by_job_id NULL, consumed_at NULL
```

一个 `consent_id` 最多绑定一个逻辑 Job；使用唯一约束 `consumed_by_job_id` 和 CAS 消费。

`review_m0_input_diagnostic`

```text
diagnostic_id PK, case_id FK, subject_type,
basis_id NULL, source_id NULL, material_id NULL,
cannot_confirm_reason, failure_stage,
locator_json NULL, evidence_digest NULL, created_at
```

恰好一个 subject ID 非空，并与 `subject_type` 匹配。ZIP 非法叶子即使不形成 `materialId`，也通过 `source_id + locator_json` 保留诊断；API 的 `diagnostics` 从本表投影。

`review_m0_job`

```text
job_id PK, case_id FK, user_id, job_type, status,
request_json, request_digest, input_fingerprint NULL,
idempotency_key, progress_completed, progress_total,
attempt, lease_owner NULL, lease_expires_at NULL,
cancel_requested_at NULL, result_type NULL, result_id NULL,
error_code NULL, error_retryable NULL, error_message NULL,
version, created_at, started_at NULL, heartbeat_at NULL, finished_at NULL
```

唯一：`(user_id, case_id, job_type, idempotency_key)`；索引：`(status, lease_expires_at, created_at)`、`(case_id, created_at)`。

`review_m0_job_checkpoint`

```text
job_id FK, checkpoint_key, checkpoint_json, checkpoint_digest,
version, updated_at, PRIMARY KEY(job_id, checkpoint_key)
```

checkpoint 只在完成一个幂等工作单元后提交；不得存未脱敏远程密钥。

`review_m0_remote_call`

```text
remote_call_id PK, job_id FK, consent_id FK,
request_digest, state, provider_request_id NULL,
response_digest NULL, created_at, updated_at
```

唯一：`job_id`（M0 每个候选提取 Job 最多一个逻辑远程调用）。`state` 只允许 `SEND_INTENT`、`RESPONSE_RECORDED`、`OUTCOME_UNKNOWN`；只有持久化 `RESPONSE_RECORDED` 后才能消费响应生成候选。供应商请求 ID 只存不含凭据的稳定标识。

`review_m0_report`

```text
report_id PK, case_id FK, job_id FK, contract_version,
engine_version, freshness, assessment, input_fingerprint,
checklist_snapshot_id FK, upload_confirmation_id FK,
basis_revision, material_set_revision, assignment_revision,
name_revision, base_report_id NULL,
missing_count, file_name_noncompliant_count,
cannot_confirm_count,
created_at, outdated_at NULL, outdated_reason NULL
```

唯一：`job_id`、`(case_id, input_fingerprint)`；索引：`(case_id, freshness, created_at)`。应用事务保证一个案例最多一个 `CURRENT`。API 的 `summary.ignoredCount` 从 `review_m0_finding_decision` 投影，不回写本表；报告生成时的三类发现计数保持不可变。

`review_m0_finding`

```text
finding_id PK, report_id FK, finding_type,
cannot_confirm_reason NULL, requirement_id NULL,
material_id NULL, stable_finding_key,
message, created_at
```

唯一：`(report_id, stable_finding_key)`。稳定 key 由类型、项目、材料和规则 ID 的规范组合摘要产生，不能由展示文案产生。

`review_m0_evidence_ref`

```text
evidence_id PK, finding_id FK, evidence_side,
evidence_kind, ref_json, ref_digest, created_at
```

`evidence_side` 只允许 `RULE`、`MATERIAL`；确定性发现由数据库约束加发布前校验保证两侧非空。

`review_m0_finding_decision`

```text
decision_id PK, report_id FK, finding_id FK,
decision_type, reason NULL, decided_by, decided_at
```

当前 ignored 状态由最后事件折叠，不覆写发现。

`review_m0_idempotency`

```text
owner_id, http_method, canonical_path, idempotency_key,
request_digest, response_status NULL, response_type NULL,
response_id NULL, state, created_at, updated_at,
PRIMARY KEY(owner_id, http_method, canonical_path, idempotency_key)
```

`state` 只允许 `IN_PROGRESS`、`COMPLETED`。保留期限不得短于对应资源；具体清理期依赖 `P-01` 用户确认。

## 10. 事务、恢复与幂等

### 10.1 同步写

- 案例修订增加、相关资源写入、当前报告过期和 `currentReportId` 清空必须同一事务。
- CAS 失败返回 `REVIEW_M0_VERSION_CONFLICT`，客户端重新读取，不盲重放修改。
- 文件服务读取不能加入数据库事务；先取得不可变文件摘要，事务内再次校验案例版本并保存快照。

### 10.2 后台任务

- API 事务内同时保存幂等记录和 `QUEUED` Job；队列通知可以事务后重试，数据库轮询是恢复兜底。
- Worker 用 CAS 取得租约，定期 heartbeat；进程退出后由租约过期恢复。
- 每个工作单元以稳定 key 写 checkpoint。远程单元在发送前原子保存 `SEND_INTENT + requestDigest`，响应成功后保存 `RESPONSE_RECORDED + responseDigest`；恢复时不得重复发送已有响应的调用。
- 若进程在 `SEND_INTENT` 后、持久化响应前退出，且供应商没有可验证的同幂等键查询结果，任务进入 `FAILED/REVIEW_M0_REMOTE_OUTCOME_UNKNOWN`，不得自动重发；用户要重试必须重新预览确认并创建新逻辑任务。这以保守失败换取不重复外发。
- 报告生成在临时内存/任务私有 checkpoint 完成，发布事务一次性插入报告、发现、证据、更新旧报告过期和 Job `SUCCEEDED`。
- 唯一约束 `(case_id,input_fingerprint)` 保证相同输入重跑返回同一逻辑结果；如需保留多次执行审计，Job 可以不同，但只能关联已有报告。
- 可重试系统错误由 Worker 有界重试；具体次数/退避是运维配置，不是产品事实。耗尽后 `FAILED`，不可生成业务发现。

## 11. 错误码与 HTTP 语义

| HTTP | `errorCode` | 语义/重试 |
| --- | --- | --- |
| 400 | `REVIEW_M0_REQUEST_INVALID` | JSON、枚举、未知字段、文件名安全规则或参数组合非法；修正请求 |
| 401 | `AUTHENTICATION_REQUIRED` | 无有效认证 |
| 404 | `REVIEW_M0_CASE_NOT_FOUND` | 案例不存在或异主 |
| 404 | `REVIEW_M0_RESOURCE_NOT_FOUND` | 依据、材料、预览、任务、清单或报告不存在或异主；`details.resourceType` 定位 |
| 409 | `REVIEW_M0_VERSION_CONFLICT` | `expectedVersion`/修订已落后；重新读取 |
| 409 | `REVIEW_M0_IDEMPOTENCY_CONFLICT` | 同一幂等键载荷不同；更换键或修正调用 |
| 409 | `REVIEW_M0_BASIS_REVISION_STALE` | 提取/预览不对应当前依据 |
| 409 | `REVIEW_M0_BASIS_CANNOT_CONFIRM` | 当前官方依据有不能可靠处理的输入；替换或移除后重新确认 |
| 409 | `REVIEW_M0_CHECKLIST_NOT_CONFIRMED` | 没有当前正式清单或仍为草稿 |
| 409 | `REVIEW_M0_CHECKLIST_STALE_BASIS` | 官方依据变化后清单未重新确认 |
| 409 | `REVIEW_M0_CHECKLIST_ACK_MISMATCH` | 确认 ID 集合与启用项目不一致 |
| 409 | `REVIEW_M0_CONDITIONAL_UNRESOLVED` | 存在 `NEEDS_USER_DECISION` |
| 409 | `REVIEW_M0_UPLOAD_NOT_CONFIRMED` | 当前清单/材料修订没有上传完毕确认；不得生成 `MISSING` |
| 409 | `REVIEW_M0_REMOTE_PREVIEW_STALE` | 预览依据或载荷摘要已变化 |
| 409 | `REVIEW_M0_REMOTE_CONSENT_REQUIRED` | 远程任务没有匹配确认 |
| 409 | `REVIEW_M0_REMOTE_CONSENT_CONSUMED` | 一次授权已绑定其他逻辑任务 |
| 409 | `REVIEW_M0_JOB_STATE_CONFLICT` | 非法任务转换或终态操作冲突 |
| 409 | `REVIEW_M0_INCREMENTAL_BASE_MISMATCH` | 增量基础或未声明输入变化；改用完整审查 |
| 415 | `REVIEW_M0_UNSUPPORTED_FILE_TYPE` | 顶层扩展不在六类正式格式；不附加 |
| 503 | `REVIEW_M0_FILE_SERVICE_UNAVAILABLE` | 文件服务瞬时系统故障；Job 失败/可重试，不是 `CANNOT_CONFIRM` |
| 503 | `REVIEW_M0_REMOTE_MODEL_FAILED` | 远程调用超时、鉴权、限流或无效响应；Job 失败，不是 `MISSING` |
| 503 | `REVIEW_M0_REMOTE_OUTCOME_UNKNOWN` | 已记录发送意图但无法确认远程是否处理；禁止自动重发，需新预览授权 |
| 503 | `REVIEW_M0_STORAGE_UNAVAILABLE` | Review 事实库不可用；可重试 |
| 503 | `REVIEW_M0_JOB_QUEUE_UNAVAILABLE` | 任务无法安全持久化/调度；可重试 |
| 500 | `REVIEW_M0_INTERNAL_ERROR` | 未分类系统错误；不回传内部细节 |

以下是发现原因，不是上述 HTTP/Job 系统错误：`PARSE_FAILED`、`ENCRYPTED`、`CORRUPTED`、`OCR_INSUFFICIENT`、`TYPE_MISMATCH`、`NESTED_ARCHIVE_NOT_ALLOWED`、`ARCHIVE_ENTRY_UNSUPPORTED`、`ARCHIVE_SAFETY_LIMIT_EXCEEDED`、`SOURCE_FILE_UNAVAILABLE`。

区分示例：文件摘要证明对象已被删除，输入本身不可取，产生 `CANNOT_CONFIRM/SOURCE_FILE_UNAVAILABLE`；文件服务连接超时无法判断对象是否存在，Job 以 `REVIEW_M0_FILE_SERVICE_UNAVAILABLE` 失败，不发布报告。
