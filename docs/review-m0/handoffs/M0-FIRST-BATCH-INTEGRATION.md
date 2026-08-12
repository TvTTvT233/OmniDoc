# Review M0 第一批基线集成与一致性审计交接

## 1. 交付结论

- 日期：`2026-08-13`
- 状态：`BASELINE_INTEGRATED / ASSET_VERIFIED / PENDING_INTEGRATION`
- 指定起始基线：`d289cf03fe57e81f78cab9ece9e2e47d84bf1474`
- 最终可执行集成基线：`c5d06aad1924e0868ff8abb1d8f615c3dcdc44d9`
- 协调分支：`review/m0-wave1-integration-019ff70c`
- 协调 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-wave1-integration-019ff70c`
- 生产代码：未新增、未修改
- Review M0 生产能力：未实现、未验证
- 默认资产门禁：`ASSET_VERIFIED`，退出 `0`
- Integrated 门禁：`PENDING_INTEGRATION`，退出 `2`

本批只完成 M0-00 契约、当前解析器行为基线与 M0-07A 验收骨架的顺序集成、冲突审计、最小一致性修正和确定性验证。不得把本交付描述为 Review M0 生产能力、端到端链路或 `AUTO_VERIFIED`。

## 2. 提交来源与拓扑

三个来源提交都以指定基线为唯一父提交，并且来源 worktree 在审计时均为干净状态：

| 顺序 | 来源提交 | 来源性质 | 协调 cherry-pick 提交 |
| --- | --- | --- | --- |
| 1 | `7253f46d0f982a24c6f32eed627d0b6dcbdcd9b4` | M0-00 冻结契约 | `934f171d367efc48b8d814198c702c84f18b2895` |
| 2 | `8cbc7d82e9e81448fa6c9ff75ce7c86da4266182` | 当前解析器与证据定位基线 | `41d9ef4e397d97dd9f8835c25e6b49bcdfabef22` |
| 3 | `e65f21528c815012e60c5831e5ceafc27c83bee2` | M0-07A 验收资产与门禁骨架 | `87d4f9ccc632304eba82206184bae2a33a83c979` |

协调修正提交：

- `c5d06aad1924e0868ff8abb1d8f615c3dcdc44d9`：只对测试、测试资源、Review M0 文档和专用脚本作冻结契约一致性修正。

拓扑：

```text
d289cf03fe57e81f78cab9ece9e2e47d84bf1474
  -> 934f171d367efc48b8d814198c702c84f18b2895
  -> 41d9ef4e397d97dd9f8835c25e6b49bcdfabef22
  -> 87d4f9ccc632304eba82206184bae2a33a83c979
  -> c5d06aad1924e0868ff8abb1d8f615c3dcdc44d9
```

三对来源/协调 cherry-pick 的稳定 patch-id 均相等，确认无冲突改写或内容遗漏。三个 cherry-pick 均无冲突。

## 3. 集成文件范围

从指定基线到 `c5d06aa` 共新增 28 个 Git 文件：

- `docs/review-m0/`：8 个契约与原任务 handoff；
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/`：8 个解析/验收测试文件；
- `ai-service/src/test/resources/review-m0/`：8 个 JSON/schema/manifest；
- `scripts/review-m0/`：4 个专用门禁文件。

协调修正没有新增生产包、生产资源、数据库迁移、前端代码或公共配置。

以下热点相对指定基线逐文件 diff 为 0：

- 根 `pom.xml` 与 `ai-service/pom.xml`；
- `ai-service/src/main/resources/application.yml`；
- `ai-service/src/main/resources/application-local.yml`；
- `AgentExecutionService.java`；
- `AgentToolRegistry.java`；
- `web/src/router/index.js`；
- `docs/PROGRESS.md`。

现有 `OmniDoc` checkout、三个来源 worktree 和其他 worktree 均未被本协调任务修改。`docs/PROGRESS.md` 未更新是因为本任务明确禁止修改，优先于仓库通用交付约定。

## 4. 一致性审计与最小修正

以下矛盾不涉及新的产品选择，已在 `c5d06aa` 中按冻结契约作最小修正。

### 4.1 包与资源所有权

来源资产位于 `com.javaee.aiservice.review.acceptance/baseline` 和 `src/test/resources/review/m0`，与 M0-00 冻结的测试包根和资源根不一致。已统一为：

```text
com.javaee.aiservice.review.m0.acceptance
com.javaee.aiservice.review.m0.baseline
ai-service/src/test/resources/review-m0/acceptance
ai-service/src/test/resources/review-m0/baseline
```

对应 Java package、classpath resource、PowerShell 路径和专项命令已同步更新。

### 4.2 公共枚举与错误码

验收 fixture 原先含有未冻结同义值，例如 `FILE_ENCRYPTED`、`FILE_CORRUPT`、`OCR_FAILED`、`REVIEW_NOT_FOUND`、`EXACT_PATTERN`、`NATURAL_LANGUAGE_AMBIGUOUS`。已改为或重构为冻结语义：

- `ENCRYPTED`、`CORRUPTED`、`OCR_INSUFFICIENT`；
- `REVIEW_M0_CHECKLIST_NOT_CONFIRMED`；
- `REVIEW_M0_UPLOAD_NOT_CONFIRMED`；
- `REVIEW_M0_REQUEST_INVALID`；
- `REVIEW_M0_RESOURCE_NOT_FOUND`；
- `FileNameRuleKind=EXACT` 且 `caseSensitive` 必须显式；
- `IGNORE` 决定后 assessment 仍为 `M0_NOT_READY`；
- 报告新鲜度字段使用 `OUTDATED`。

用户未确认上传完毕时不再伪装成 `CANNOT_CONFIRM`，而是拒绝审查、报告不创建、`MISSING=0`。

### 4.3 规则和材料证据

- 无可靠定位的官方提取项不得被接受为 `OFFICIAL_EXTRACTED`，也不得发布正式清单；没有新增一个未冻结的公共原因码。
- 用户新增保持 `origin=USER_ADDED/authorship=USER_REWRITTEN`。
- 用户改写官方提取项保持 `origin=OFFICIAL_EXTRACTED`，保留 `sourceRefs`，但不把改写文字声称为官方原文。
- 可输出的 `MISSING` fixture 同时要求规则侧 `USER_RULE_REVISION` 和材料侧 `MATERIAL_SET_SNAPSHOT`。

### 4.4 正式格式、ZIP 和非破坏性

- 正式格式精确限定为 PDF/DOCX/XLSX/JPG/PNG/ZIP 六类。
- 删除来源资产中把 `.jpeg` 作为正式格式接受的漂移；JPG 正式扩展固定为 `.jpg`。
- 每类正式格式在 manifest 中只出现一次。
- ZIP 明确 `containerOnly=true`，叶子白名单精确为 PDF/DOCX/XLSX/JPG/PNG。
- ZIP 安全诊断映射到冻结 `CannotConfirmReason`：`ARCHIVE_SAFETY_LIMIT_EXCEEDED`、`NESTED_ARCHIVE_NOT_ALLOWED`、`ENCRYPTED` 或 `TYPE_MISMATCH`；安全 ZIP 不伪造原因码。
- `submissionFileName` fixture 仍只改变逻辑名；原始文件名、`objectKey` 和内容摘要保持不变，下载对象为派生副本。

### 4.5 机器报告

来源门禁使用了自拟的 `review-m0-v1` 报告结构。已对齐冻结机器报告：

- `schemaVersion=REVIEW_M0_ACCEPTANCE_REPORT_V1`；
- `contractVersion=REVIEW_M0_CONTRACT_V1`；
- 固定 `counts/p0/external/readinessMissing/unverified`；
- Asset/Pending 状态强制 `finalAcceptance=false`、`manualStatus=NOT_READY`；
- `AUTO_VERIFIED` 才允许 `finalAcceptance=true`、`manualStatus=MANUAL_PENDING`、全部 P0 计数为 0 且 `readinessMissing=[]`；
- 执行故障不再生成自拟 `FAILED` 验收状态报告，而是非零退出并删除该次残留报告。

Asset 和 Pending 报告中的 P0 生产计数保持 `null`，不会以资产 fixture 通过伪造生产 P0 为 0。

### 4.6 解析建议与公共契约隔离

`source-anchor-candidates.json` 继续明确：

- `contractStatus=NON_FINAL_SUGGESTION_FOR_M0_00`；
- `publicApiContract=false`。

格式专用定位字段和内部失败分类只是 test-only 建议，没有加入 M0-00 公共 JSON、枚举或错误码。已冻结的公共 `OfficialBasisSourceLocator` 与 `CannotConfirmReason` 仍是唯一公共契约；内部不安全 ZIP 路径建议映射到 `ARCHIVE_SAFETY_LIMIT_EXCEEDED`。

### 4.7 待决项完整性

完成定义原文字面只列 `P-01` 至 `P-12`，而产品待决表实际包含 `P-13`。已机械修正为 `P-01` 至 `P-13`。本任务未决定其中任何一项。

## 5. 实际验证

以下最终结果均在干净、已提交的 `c5d06aad1924e0868ff8abb1d8f615c3dcdc44d9` 上运行。

### 5.1 解析专项

```powershell
.\mvnw.cmd -q -pl document-service -am dependency:build-classpath `
  -Dmdep.outputFile=target/m0-pb-runtime-classpath.txt `
  -Dmdep.includeScope=runtime

.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service `
  '-Dtest=com.javaee.aiservice.review.m0.baseline.*Test' test
```

结果：

- 依赖 classpath 生成退出 `0`；
- 13 项发现，11 项通过，2 项跳过；
- Failures 0，Errors 0；
- `BUILD SUCCESS`。

两个跳过项是 JPG/PNG 的真实 Tess4J OCR 边界。本机 PATH 未找到 `tesseract`，没有安装外部软件或伪造 OCR 成功。

### 5.2 验收专项

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service `
  '-Dtest=ReviewM0FixtureContractTest,ReviewM0ReportSchemaTest,ReviewM0IntegrationSelectorContractTest,ReviewM0StaticGateContractTest' `
  test
```

最终结果：26/26，Failures 0，Errors 0，Skipped 0，`BUILD SUCCESS`。

修正过程中有一次真实失败：26 项中 1 个 fixture 断言仍期待旧 `CANNOT_CONFIRM` 字段。删除该未冻结旧断言并改为“官方提取不接受、清单不发布”后，完整专项重跑通过。

### 5.3 默认资产门禁

```powershell
scripts\review-m0\run-m0-acceptance.cmd
```

结果：

- 退出 `0`；
- 26/26，Failures 0，Errors 0，Skipped 0；
- 敏感信息门扫描 890 个 Git 可见文本文件，无命中；
- 报告 `ASSET_VERIFIED`；
- `baselineCommit=c5d06aad1924e0868ff8abb1d8f615c3dcdc44d9`；
- `finalAcceptance=false`；
- `manualStatus=NOT_READY`；
- 生产行为未执行。

### 5.4 Integrated 门禁

```powershell
scripts\review-m0\run-m0-acceptance.cmd -Integrated
```

结果：

- 真实退出 `2`；
- Asset 自检仍为 26/26，Failures 0，Errors 0，Skipped 0；
- 敏感信息门扫描 890 个 Git 可见文本文件，无命中；
- 报告 `PENDING_INTEGRATION`；
- `baselineCommit=c5d06aad1924e0868ff8abb1d8f615c3dcdc44d9`；
- `finalAcceptance=false`；
- `manualStatus=NOT_READY`；
- 15 个 P0 生产计数全部为 `null`；
- readiness 缺口精确为：
  - `REVIEW_M0_PRODUCTION_ADAPTER_NOT_DECLARED`；
  - `REVIEW_M0_EXACT_INTEGRATED_TEST_SELECTION_NOT_DECLARED`；
  - `REVIEW_M0_STATIC_SCAN_ROOTS_NOT_DECLARED`。

这满足“无生产实现时 Integrated 必须非零并为 `PENDING_INTEGRATION`”的要求。

### 5.5 静态和结构检查

- 8 个 JSON 文件均可由 PowerShell `ConvertFrom-Json` 解析；
- 两份 PowerShell 脚本 parser error 为 0；
- 8 个 Java 测试文件的 package 与冻结目录一致；
- `git diff --check` 通过；
- 三个来源提交与三次 cherry-pick 的稳定 patch-id 逐一相等；
- 指定禁改热点相对基线逐文件 diff 为 0；
- 集成 worktree 与来源/原 checkout 状态均已核对。

静态预检曾有一次包路径检查命令把类名误纳入期望 package，产生检查器自身的假失败；修正表达式后 8/8 路径检查通过，不是源码缺陷。

## 6. 当前解析基线差异

当前通用生产解析器的行为刻画仍为：

| 格式 | 当前结果 | 定位能力 |
| --- | --- | --- |
| PDF | 可提取扁平文本 | 页/块/坐标丢失，`TEXT_ONLY` |
| DOCX | 可提取段落和表格文本 | 正文顺序与段落/单元格定位丢失，`TEXT_ONLY` |
| XLSX | 未分派，原始 OOXML 字节 UTF-8 fallback | `UNAVAILABLE` |
| JPG/PNG | 代码分派到 Tess4J | 仅全文字符串，无 block/bbox/confidence；真实 OCR 未验证 |
| ZIP | 未枚举或安全展开，原始字节 UTF-8 fallback | `UNAVAILABLE` |

当前 parser 只按扩展名分派、吞掉 typed parse failure，不能作为 M0-00 的类型校验、ZIP 安全、可靠定位或稳定失败语义实现。该结论只是行为基线，没有修改生产 parser。

## 7. 未验证与剩余边界

- 没有 Review M0 生产包、数据库表、迁移、Controller、文件适配、ZIP 读取、确定性引擎或生产装配。
- 没有真实 MySQL、文件服务、文档服务、MinIO、Redis、消息队列、远程模型、浏览器或双用户 JWT 验证。
- 没有真实 OCR、traineddata、扫描件、复杂 Office 文件、加密文件、超大文件或真实安全 ZIP 展开验证。
- 没有真实 `submissionFileName` 下载副本链路；当前只验证冻结不变量 fixture。
- 没有执行完整 `ai-service` 回归、文件服务回归、文档服务回归、应用上下文或端到端服务启动；本批只运行明确要求的解析/验收专项与两种 M0 门禁。
- 当前环境没有可用的 Python `jsonschema` 包或其他已安装 Draft 2020-12 validator；未执行第三方完整 JSON Schema 标准验证。Java 契约、PowerShell JSON 解析和门禁运行时精确白名单已通过。
- `P-01` 至 `P-13` 全部保持待决，本任务没有增加隐藏默认值。
- parser 定位字段和内部诊断分类仍是非公共建议；不得由后续实现直接升级公共契约。
- Integrated 成功路径和 `AUTO_VERIFIED` 必须等待 M0-01 至 M0-07 生产任务真实集成后再执行。

## 8. 后续任务入口

1. **M0-01 剩余只读基线**：从协调者指定的已提交共同基线开始，补齐 `docs/review-m0/handoffs/M0-01.md` 中的文件服务所有者、稳定摘要/流式读取、ZIP 单条目能力、认证 userId、JDBC 迁移方式、结构化错误映射和应用上下文盘点；只记录缺口，不顺手修生产。
2. **M0-02 领域/存储/任务基础设施**：只能在 M0-01 完整交接被协调者集成后启动；不得改公共 POM/YAML、Agent 热点、路由或进展文档。
3. **解析能力缺口**：由对应后续实现任务按 M0-00 已冻结公共定位与失败语义设计适配；如确需采用候选格式专用字段，必须先走独立契约变更，不能把建议文件直接视为公共 schema。
4. **M0-07A 后续适配**：生产实现未集成前保持 selector `PENDING_INTEGRATION`。只有真实精确测试类、15 个目标方法、生产静态扫描根和无正文 evidence 全部可用时，才允许切换为 `READY`。
5. **产品待决**：P-01 至 P-13 继续由产品/用户确认；本批没有为后续任务作任何选择。

后续任务不得以本批 Asset 结果声称文件服务、解析服务、数据库、远程模型或 Review M0 生产行为已验证。
