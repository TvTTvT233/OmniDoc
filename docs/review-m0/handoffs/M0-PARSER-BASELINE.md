# M0-PB 解析与证据定位基线

## 1. 状态与边界

- 状态：`TEST_BASELINE_VERIFIED / REAL_OCR_AND_SCANNED_FILES_UNVERIFIED`
- 起始基线：`d289cf03fe57e81f78cab9ece9e2e47d84bf1474`
- 分支：`test/m0-pb-parser-baseline`
- 工作树：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\m0-pb-parser-baseline`
- 性质：只读现状刻画、测试夹具和候选契约建议；不是 Review 生产实现。
- 未修改 `document-service`、`ai-service` 主代码、父 POM、公共配置、共享 Agent 热点、Web 路由或 `docs/PROGRESS.md`。`docs/PROGRESS.md` 未更新是因为本任务的具体授权明确禁止修改，优先于仓库通用交付约定。

本基线只回答“当前项目真实能做到什么以及丢失什么定位信息”。它不证明完整服务、文件服务、Review 流程或生产环境可用，也不决定 M0-00 的最终公共契约。

## 2. 验证方法

当前唯一的通用生产解析器是：

- `document-service/src/main/java/com/javaee/documentservice/util/DocumentParserUtil.java`
- `DocumentServiceImpl` 下载文件字节后，以原文件名扩展名调用 `DocumentParserUtil.parseDocument(...)`，得到一个扁平 `String`。

`ai-service` 没有 POI、PDFBox 或 Tess4J 依赖，而本任务不能修改 POM，也不能在 `document-service` 增加测试。因此专项测试采用以下只读方式：

1. Maven 只生成 `document-service` 既有运行依赖的 classpath 文件；
2. JUnit 用 JDK `JavaCompiler` 编译工作树中原样的 `DocumentParserUtil.java` 到 `@TempDir`；
3. 隔离 `URLClassLoader` 反射调用该生产类；
4. PDF、DOCX、XLSX、JPG、PNG、ZIP 全部由测试生成最小无敏感信息字节，不保存真实用户材料。

这不是 production parser 的复制实现。测试每次都读取并编译当前工作树中的生产源码；生产源码变化会直接改变基线结果。

## 3. 六类正式格式的当前能力

| 格式 | 当前分派 | 内容能力 | 当前证据定位 | 基线结论 |
| --- | --- | --- | --- | --- |
| PDF | 支持 | PDFBox `PDFTextStripper` 能从两页合成 PDF 提取两段文本 | 不保留页码、页内块、坐标或字符范围 | `TEXT_ONLY`；不能形成可靠页级 `SourceAnchor` |
| DOCX | 支持 | POI 能提取顶层段落和表格单元格文本 | 不返回段落/表格/行/列索引；先遍历所有顶层段落，再遍历所有表格，破坏正文中表格与段落的交错顺序 | `TEXT_ONLY`；不能形成可靠段落/单元格定位 |
| XLSX | 不支持 | 尽管 `document-service` 已有 POI OOXML，parser 未分派 XLSX；合法 B3 单元格不会被提取，而是把整个 ZIP/OOXML 字节按 UTF-8 解码 | 无 sheet、cell 或范围定位 | `UNAVAILABLE` |
| JPG | 支持分派 | 代码构造 Tess4J，固定请求 `eng+chi_sim`，只调用 `doOCR` 返回全文字符串 | 无 OCR block、坐标或置信度 | 代码边界为 `TEXT_ONLY`；本机真实 OCR 未验证 |
| PNG | 支持分派 | 与 JPG 相同 | 无 OCR block、坐标或置信度 | 代码边界为 `TEXT_ONLY`；本机真实 OCR 未验证 |
| ZIP | 不支持 | 不枚举条目；合法、路径穿越和高压缩比 ZIP 均按原始 UTF-8 字节回退 | 无 entry path、entry digest、容器层级或父锚点 | `UNAVAILABLE`，也没有生产 ZIP 安全边界 |

补充的确定性发现：

- 扩展名是唯一分派依据；当前 parser 不做 MIME、魔数或扩展名与实际内容一致性校验。
- 空字节直接返回空字符串。
- 已识别格式损坏时，异常被捕获并把原始字节按 UTF-8 返回；例如损坏 PDF 返回 `NOT_A_REAL_PDF`，没有 typed failure。
- OCR 图像无法解码或 Tess4J 抛错时返回空字符串；没有区分图片损坏、OCR runtime 缺失、语言数据缺失、识别质量不足或“确实无文字”。
- 生产 `src/main/java` 未发现 `ZipInputStream`、`ZipFile`、Commons Compress ZIP 读取、条目数/展开大小/压缩比/嵌套深度限制。
- 文件服务可把 ZIP 标记为 archive/MIME，也允许较大的上传请求，但这不等于安全展开或条目解析能力。

## 4. 合成 fixture

`SyntheticReviewFixtureFactory` 在内存中生成：

- 两页、每页一个唯一 token 的有效 PDF；
- 段落—表格—段落交错的有效 DOCX，表格为 2 行 × 2 列；
- `Evidence!B3` 含唯一 token 的有效 XLSX，并用 `document-service` 已有 POI 反射自检该单元格；
- 含大号无敏感英文 token 的有效 JPG 与 PNG；
- 一个正常 ZIP、一个含 `../`/绝对路径/Windows drive 路径的 ZIP、一个约 64 KiB 高重复内容的高压缩比 ZIP。

ZIP 恶意边界 fixture 只验证当前 parser 没有进入 ZIP 处理，并未把 64 KiB、100 倍压缩比等测试数值冻结为产品容量。M0-00 中容量上限仍是待确认项。

## 5. `SourceAnchor` 候选（非最终契约）

机器可读建议位于 `source-anchor-candidates.json`，明确标记为：

- `contractStatus=NON_FINAL_SUGGESTION_FOR_M0_00`
- `intendedPlacement=M0_00_SOURCE_REFS_SOURCE_LOCATOR`
- `publicApiContract=false`

建议保留的共同字段：

- `sourceId`、`sourceVersion`、`mediaType`、`contentDigest`
- `locatorKind`
- `exactText`、`textDigest`
- `anchorQuality=EXACT|APPROXIMATE|TEXT_ONLY|UNAVAILABLE`

格式专用候选：

| 格式 | 候选字段 |
| --- | --- |
| PDF | `pageNumber`、`boundingBox`、`charStart`、`charEnd` |
| DOCX | `bodyElementIndex`、`paragraphIndex`、`tableIndex`、`rowIndex`、`cellIndex`、`runIndex` |
| XLSX | `sheetName`、`sheetIndex`、`cellReference`、`rowIndex`、`columnIndex`、`formula`、`formattedValue` |
| 图片 | `pageNumber`、`blockIndex`、`boundingBox`、`ocrText`、`confidence`、`ocrEngine`、`ocrLanguage` |
| ZIP | `entryPath`、`entryIndex`、`entryDigest`、`containerDepth`、`parentArchiveAnchor` |

这些字段旨在让未来实现可被测试，不要求每个格式填满所有字段。缺少可靠坐标时必须降级 `anchorQuality`，不得由模型补造。

## 6. 失败分类候选及 M0-00 对齐

`source-anchor-candidates.json` 还冻结了 test-only 内部诊断候选，包括格式不支持、扩展名/内容不一致、损坏/加密、不可提取、OCR runtime/语言数据、定位不可用、ZIP 不安全路径/上限/嵌套/加密条目、依赖不可用和部分提取。

每项都有 `stage`、`retryable` 和 `m000Disposition`，用于区分：

- 输入本身确定不可处理，可映射为 M0-00 的 `CANNOT_CONFIRM` 原因；
- 基础设施或运行依赖瞬时不可用，应使 Job `FAILED`，不能伪装成业务 `CANNOT_CONFIRM`；
- 定位不可靠时不得确认官方提取候选。

它们不是新增公共 HTTP error code 或 `CannotConfirmReason`，最终枚举仍归 M0-00 决定。

### 与只读 M0-00 协调快照的差异建议

本任务基线提交 `d289cf0` 不包含 M0-00 文档。为避免抢夺契约，本任务只读查看了相邻 M0-00 worktree 在 2026-08-13 的未提交协调快照；该快照仍可能变化：

1. M0-00 示例把 `sourceRefs` 外层表示为 `basisId + sourceLocator + quotedText`。本任务建议字段只放入 `sourceLocator` 内，不改变该外层结构。
2. M0-00 示例的 `sourceLocator={page, block}` 对 PDF 有方向，但尚未表达 DOCX table/cell、XLSX sheet/cell、图片 bbox/confidence、ZIP entry/parent archive。建议最终契约采用可判别的格式专用 locator。
3. M0-00 已声明 ZIP 路径穿越应产生 `CANNOT_CONFIRM`，但只读快照中的公开原因枚举没有专门的不安全路径原因。建议 M0-00 明确映射到 `ARCHIVE_SAFETY_LIMIT_EXCEEDED`，或增加独立稳定原因；本任务不替其决定。
4. M0-00 正确区分输入 OCR 质量不足与系统依赖失败。本任务的 `OCR_RUNTIME_UNAVAILABLE`/`OCR_LANGUAGE_DATA_MISSING` 仅用于保证未来实现不要把环境缺失写成 `OCR_INSUFFICIENT`。
5. M0-00 明确 M0 不读取提交材料正文，OCR 只可能用于官方依据候选提取。因此本基线的 PDF/DOCX/XLSX/图片正文 anchor 建议只用于官方依据提取或后续阶段预留；在 M0 正式审查中，提交材料只做类型、容器、命名、分配和计数所需的结构性定位。
6. M0-00 要求扩展名、签名和可打开性共同校验；当前生产 parser 只信任扩展名并吞掉 parse failure。实现任务不能把当前 fallback 作为满足 M0-00 的证据。

## 7. 实际验证记录

### 依赖 classpath

在仓库根运行：

```powershell
& '.\mvnw.cmd' '-q' '-pl' 'document-service' '-am' `
  'dependency:build-classpath' `
  '-Dmdep.outputFile=target/m0-pb-runtime-classpath.txt' `
  '-Dmdep.includeScope=runtime'
```

结果：完成并生成 `document-service/target/m0-pb-runtime-classpath.txt`；文件含 274 个 classpath entry，确认包括 PDFBox 3.0.2、POI OOXML 5.2.5、Tess4J 5.8.0 和 reactor 内 `common` artifact。`target` 构建产物未纳入 Git。

### 专项测试

```powershell
& '.\mvnw.cmd' '-pl' 'ai-service' `
  '-Dtest=com.javaee.aiservice.review.baseline.*Test' 'test'
```

最终结果：`BUILD SUCCESS`；13 项发现，11 项通过，2 项跳过，Failures 0，Errors 0。

两个跳过项分别是 JPG 和 PNG 的真实 Tess4J OCR 边界，`@Disabled` 文本明确要求本机 native Tesseract、`eng`/`chi_sim` traineddata，并说明合成清晰图片不是扫描件精度证据。

首次运行曾真实失败：候选契约测试 3 项通过，但 parser 测试在 `@BeforeAll` 使用实例级 `@TempDir` 时得到 `null`，总计 4 项发现、1 个 Errors、`BUILD FAILURE`。将 class 生命周期临时目录改为 static 后重跑通过；该失败是测试生命周期问题，不是 parser 能力结论。

### 环境检查

```powershell
Get-Command tesseract -ErrorAction SilentlyContinue
```

结果：本机 PATH 未发现 `tesseract`。未尝试安装外部软件，未伪造 OCR 成功。

## 8. 未验证与后续实现风险

- 未运行真实 Tesseract、`eng`/`chi_sim` traineddata、真实扫描 PDF、真实 JPG/PNG、手写体、旋转/低清/多栏材料或 OCR 置信度标定。
- 未验证加密 PDF/DOCX/XLSX、超大文件、复杂字体、批注、文本框、页眉页脚、合并单元格、公式计算、隐藏 sheet、宏、损坏 OOXML 等边界。
- 未运行文件服务、文档服务、数据库、MinIO、消息队列、完整服务或 Review 端到端。
- 合成 PDF/DOCX/XLSX 只证明最小结构与当前行为，不构成 Office/Adobe 互操作或生产兼容性证据。
- ZIP 尚无生产实现，未执行真实安全展开；路径规范化、重复路径、Unicode、symlink、加密、CRC、条目数、单项/总展开量、压缩比、超时和递归限制均待拒绝优先实现。
- 当前 parser 以 `String` 作为唯一返回类型，未来实现若仍无 typed extraction result，将无法同时表达内容、anchor、部分成功和稳定失败分类。

建议后续 parser 实现以这些测试资产为输入基线，将当前行为刻画测试替换/扩展为目标契约测试，并由 M0-00 所有者最终确认 `sourceLocator` 与 `CannotConfirmReason` 的公共形态。
