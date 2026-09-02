# Review M0 B-FIX2 — Review 下载完整性响应头修复交接

## 1. 结论与边界

- 状态：`B_FIX2_READY`。
- 固定候选基线：`97f31054ff9ae6bd9c78e0c0eb737d64fbaf56bc`。
- fix 分支：`fix/review-m0-b-fix2-integrity-header-r1`。
- 独立 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-b-fix2-integrity-header-r1`。
- 输入 B-R2 validation 分支：`validation/review-m0-r4-b-r2-01a05da7`；runner 内容提交：`10155ee489ec77c40e910eb8698a17425b06394f`；证据提交：`d3d8f3dd969f70cd94165359812afce97c7b9fbc`。
- `milestone/review-m0` 在交付前后均保持 `fa7f1d8f8b3df984db84681bd15e55bd96c9a127`。
- B 仍为 `B_PROBE_FAILED`，Formal B1-B8 仍为 `NOT_RUN`，C 仍为 `C_NOT_AUTHORIZED`。本任务没有运行 B Probe/Formal B、完整 A/B、C/D，没有合并、部署或移动 milestone。

## 2. 失败事实与根因

B-R2 Probe P2 的普通 PDF 黄金链已证明：Review HTTP 下载为 200、无 `readError`、响应长度与 `Content-Length` 一致且字节正确，但 `ETag`、`Digest`、`X-Content-SHA256` 全部缺失；第 15 个断言 `p2.download.integrityHeaders` 因此失败，前 14 个断言已通过。

只读归因确认 `ReviewM0Controller.download` 原先只设置 `Content-Type`、`Content-Disposition` 和已知时的 `Content-Length`。可信 SHA-256 已经存在于 owner-aware、digest-bound 数据链中，却没有从 `DownloadPayload` 传播到 Review HTTP 响应。这是产品契约缺口，不是 runner 大小写或取值错误。

进一步审计发现普通文件存在一个必须同时封闭的竞态：服务先取得 source/current snapshot，再按 fileId 打开内容。如果两步之间摘要事实变化，不能让旧摘要头绑定新流。因此本修复让普通文件打开显式携带并复核预期 snapshot；不一致在响应构造前 fail closed。

## 3. 既有 header 契约与可信摘要来源

本修复没有从 Probe 的“任一摘要头”断言发明协议，而是复用 file-service 现有成功下载合同，并在 Review 成功响应同时输出三者：

- `ETag: "<lowercase-sha256-hex>"`：强 ETag；只在内容已经由 owner-aware 预提交链按同一摘要验证后生成。
- `X-Content-SHA256: <lowercase-sha256-hex>`：项目既有 64 位小写 SHA-256 十六进制格式。
- `Digest: sha-256=<base64(raw-32-byte-sha256)>`：规范算法标记；Base64 输入是同一十六进制值解码后的 32 个原始摘要字节，不是十六进制文本本身。

三者由一个 `DownloadPayload.contentDigest` 生成，构造器拒绝 null、非小写或非 64 位 SHA-256，因此不会彼此漂移。

可信来源如下：

1. 普通材料：材料摘要必须等于创建材料时冻结的 source digest；当前 owner-aware metadata 必须与 source 的 fileId、原名、声明类型、长度、摘要和 storageVersion 全部一致；打开内容前再次复核同一个 expected snapshot。生产 `HttpOwnedFileByteSource` 只接受 file-service 已预提交验证且返回同一 `X-Content-SHA256`、`Content-Length` 和内容签名的 200 流。响应摘要取这个已绑定 current snapshot 的 SHA-256。
2. ZIP 单叶：继续使用 `materialId -> normalizedEntryPath + material.contentDigest` 唯一定位；B-FIX1 在暴露 payload 前完整消费并关闭受限叶流，完成所选叶摘要、剩余归档安全扫描、ZIP 源摘要和稳定元数据校验。响应摘要只取所选 material/叶摘要，不取整个 ZIP、首叶、同后缀其他叶或未经验证的数据库替代值。
3. `submissionFileName` 只参与安全 `Content-Disposition`，不参与摘要；Unicode 改名不改变内容摘要。

Review 没有直连数据库或 MinIO，也没有绕过 owner-aware file-service。没有新增正文、JWT、凭据、objectKey/bucket、摘要值或预签名 URL 日志。

## 4. 普通与 ZIP 数据流及失败保证

### 4.1 普通文件

`MaterialDownloadService` 先验证材料/source/current snapshot，再调用 `ReviewM0FileAccess.openFile(ownerId,fileId,expectedSnapshot)`。`VerifiedReviewM0FileAccess` 在打开流前重新读取 owner-aware metadata 并比较全部不可变事实；生产内容端由 file-service 的磁盘 staging 完成有界/非堆内预提交长度、SHA-256、短读、长读和 EOF 验证，之后才产生 200 和摘要头。ai-service 仍流式转发普通内容，没有把无界文件整体读入内存。

### 4.2 ZIP 单叶

B-FIX1 的有界预验证保持不变：只缓存既有最大 entry 限制内的选中叶；完整扫描 ZIP 后才创建响应 payload，源流在暴露响应前关闭。entry/总解压量/压缩比/条目数/路径深度/处理时限限制均未扩大；成功和失败都不创建临时叶 payload，因此没有临时 payload 残留。

### 4.3 非成功响应

数据库摘要错配、MinIO 等长篡改、短/长读、对象打开/读取异常、延迟 EOF 异常、硬上限和 ZIP 后置校验失败均在成功响应提交前映射为冻结 503/fail-closed。401、跨 owner/不存在 404 及以上 503 响应均不携带 `ETag`、`Digest` 或 `X-Content-SHA256`，也不暴露完整原字节。

JWT principal、owner 隔离、文件类型范围、ZIP 容器语义、单对象不变量、`submissionFileName`、安全文件名、CAS、错误 envelope、LOCAL_ONLY、报告不可变和日志边界均未改变。

## 5. 针对性回归

### 5.1 B-FIX2 精确组合

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am `
  '-Dtest=OwnerAwareFilePrecommitIntegrityHttpTest,ReviewM0ApiContractTest#ordinaryUnicodeDownloadPreservesBytesMetadataAndOwnerIsolation+zipDownloadReturnsOnlyTargetLeafAndUnsupportedTopLevelIs415+unavailableDownloadReturnsFrozen503WithoutSuccessIntegrityHeadersOrOriginalBytes,MaterialLifecycleServiceTest#ordinaryAndZipDownloadsReturnOnlyTargetBytesWithCurrentUnicodeName+zipDownloadValidationFailureIsRejectedAndClosedBeforePayloadIsExposed+topLevelMaterialDigestMismatchFailsBeforeAResponsePayloadIsExposed+ordinaryDownloadRemainsStreamingWithoutPreloadingTheUnboundedPayload,VerifiedReviewM0FileAccessTest,ReviewM0SecurityFilterChainTest#everyMissingOrInvalidAuthenticationFormUsesTheSameFrozen401Envelope,HttpOwnedFileByteSourceTest#productionAdapterReadsSameOwnerMetadataAndDigestBoundBytes+productionAdapterReturnsTheExactDigestBoundZipLeaf' `
  '-Dsurefire.failIfNoSpecifiedTests=false' -DforkCount=1 '-DargLine=-Xms96m -Xmx384m' test
```

结果：file-service `10 / 10 / 0 / 0 / 0`，ai-service `22 / 22 / 0 / 0 / 0`，合计 `32 / 32 / 0 / 0 / 0`（discovered/passed/failure/error/skip）。

覆盖事实包括：

- 普通 PDF 的实际 HTTP 字节、长度和三种精确 header 编码重新计算一致；header 名使用混合大小写读取也成功。
- 多叶 ZIP 选择可区分的第二个 PDF；摘要匹配第二叶，不匹配首叶或整个 ZIP；长度匹配所选叶。
- 安全 Unicode 改名后 `Content-Disposition` 正确、响应字节和摘要不变。
- 数据库/材料摘要错配、等长篡改、短/长读、打开/读取异常、延迟 EOF、硬上限和 unavailable 均在 200/成功头之前失败。
- 缺失/无效 JWT、跨 owner 和不存在保持 401/404 且没有成功摘要头。
- 普通路径在 payload 暴露前 read count 为 0；ZIP 成功/失败在 payload 暴露前关闭源流，staging/临时 payload 断言均为零残留。
- expected snapshot 在流打开前变化时被拒绝，封闭摘要头与响应流之间的竞态。

### 5.2 B-FIX1 精确回归

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am `
  '-Dtest=MaterialLifecycleServiceTest#ordinaryAndZipDownloadsReturnOnlyTargetBytesWithCurrentUnicodeName+zipDownloadValidationFailureIsRejectedAndClosedBeforePayloadIsExposed,HttpOwnedFileByteSourceTest#productionAdapterReturnsTheExactDigestBoundZipLeaf' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am `
  '-Dtest=ReviewM0ApiContractTest#ordinaryUnicodeDownloadPreservesBytesMetadataAndOwnerIsolation+zipDownloadReturnsOnlyTargetLeafAndUnsupportedTopLevelIs415' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果分别为 `3 / 3 / 0 / 0 / 0` 和 `2 / 2 / 0 / 0 / 0`。

## 6. 冻结回归与直接相关 selector

| 选择器/入口 | discovered / passed / failure / error / skip |
|---|---:|
| `archive.*Test,file.*Test,material.*Test`（M0-04） | `88 / 88 / 0 / 0 / 0` |
| `api.*Test,config.*Test`（M0-06） | `39 / 39 / 0 / 0 / 0` |
| `CurrentFileServiceBaselineTest,HttpOwnedFileByteSourceTest,VerifiedReviewM0FileAccessTest,ReviewM0ConfigurationTest`（owner-aware） | `21 / 21 / 0 / 0 / 0` |
| `FileApplicationTests,OwnerAwareFilePrecommitIntegrityHttpTest,OwnerAwareFileSecurityTest,OwnerAwareFileServiceTest,OwnedFileDownloadStagingTest,OwnerAwareJwtCompatibilityR1Test,SensitiveLoggingR1Test`（F3/F4 superset） | `29 / 29 / 0 / 0 / 0` |
| Review/file/JWT/owner/CAS/ZIP/submissionFileName/logging 直接相关组合 | `152 / 152 / 0 / 0 / 0` |
| `run-m0-acceptance.cmd -Integrated` 聚合 | `41 / 41 / 0 / 0 / 0` |
| `ReviewM0IntegratedEvidenceAdapter` | `15 / 15 / 0 / 0 / 0` |

Integrated 报告为 `AUTO_VERIFIED`；15 个 P0 计数全部为 0，`readinessMissing=[]`、`unverified=[]`。其 `external` 字段均明确为 `NOT_RUN`，没有把本地 integrated adapter 结果冒充真实外部 B 验证。

直接相关组合命令：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am `
  '-Dtest=OwnerAwareFilePrecommitIntegrityHttpTest,OwnerAwareFileSecurityTest,OwnerAwareFileServiceTest,OwnerAwareJwtCompatibilityR1Test,SensitiveLoggingR1Test,ReviewM0SecurityFilterChainTest,ReviewM0ApiContractTest,ReviewM0ApiIdempotencySnapshotTest,MaterialLifecycleServiceTest,ContentDispositionTest,ZipArchiveInspectorTest,VerifiedReviewM0FileAccessTest,HttpOwnedFileByteSourceTest,JdbcReviewM0MaterialDigestKeyTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' -DforkCount=1 '-DargLine=-Xms96m -Xmx384m' test
```

## 7. 构建、静态检查与冻结边界审计

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests compile
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am '-Dmaven.test.skip=true' package
.\scripts\review-m0\run-m0-acceptance.cmd -Integrated -ReportDirectory ai-service\target\b-fix2-integrated
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& 'scripts/review-m0/assert-no-sensitive-data.ps1' -RepositoryRoot '.' -ReportPath 'ai-service/target/b-fix2-integrated/review-m0-acceptance-report.json' -StaticScanRoots @('file-service/src/main/java/com/javaee/fileservice','ai-service/src/main/java/com/javaee/aiservice/review/m0')"
git diff --check
```

- compile 与 package：`common`、`file-service`、`ai-service` 均 `BUILD SUCCESS`，两个服务 fat Jar 均生成。
- 敏感信息扫描：1222 个 Git 可见文本文件，0 个 secret/fixture PII 命中。
- `git diff --check`：通过；只有 Windows checkout 的 LF/CRLF advisory，无空白错误。
- 首次 package 调用未引用 `-Dmaven.test.skip=true`，PowerShell/Maven 将其解析为无效 lifecycle phase，在任何模块构建前退出；引用参数后成功。首次直接执行扫描脚本被本机 execution policy 拒绝，按仓库约定使用 `-ExecutionPolicy Bypass` 后成功。这两项是调用层问题，不是产品或测试失败。
- 相对固定基线没有修改冻结文档、冻结 skip、B validation runner/旧证据、`scripts/review-m0`、`docs/PROGRESS.md`、P08/P09 决策或任何 acceptance fixture/report schema。
- 没有创建 `.diff`/`.patch` 文件。

## 8. 相对 `97f3105...` 的文件清单

产品文件：

- `ai-service/src/main/java/com/javaee/aiservice/review/m0/api/ReviewM0Controller.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/file/ReviewM0FileAccess.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/file/VerifiedReviewM0FileAccess.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/material/DownloadPayload.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/material/MaterialDownloadService.java`

测试文件：

- `ai-service/src/test/java/com/javaee/aiservice/config/ReviewM0SecurityFilterChainTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiContractTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/file/VerifiedReviewM0FileAccessTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/material/MaterialLifecycleServiceTest.java`
- `file-service/src/test/java/com/javaee/fileservice/controller/OwnerAwareFilePrecommitIntegrityHttpTest.java`

交接文件：

- `docs/review-m0/handoffs/B-FIX2-REVIEW-DOWNLOAD-INTEGRITY-HEADER-R1.md`

## 9. 未运行范围与剩余风险

未运行 B Probe、Formal B1-B8、完整 A/B、真实外部 MySQL/MinIO/document/OCR/remote model/browser/UI，也未启动 C/D。这些仍由新候选的 Probe + Formal B 决定；本专项不能把 B 状态改为通过或授权 C。

剩余风险：普通 Review 响应依赖部署中的 file-service 继续遵守现有预提交 staging 与摘要头合同；该合同已由真实嵌入式 HTTP 回归覆盖，但本任务没有运行真实外部 MinIO 专项。ZIP 并发仍具有 B-FIX1 已记录的、受既有 entry 上限约束的单叶堆占用；本修复没有扩大容量或增加新的缓存。
