# Review M0 B-FIX1 — ZIP 单叶下载字节修复交接

## 1. 结论与边界

- 状态：`B_FIX1_READY`。
- 固定基线：`a8e3440232f50c91b17064a5f8bbcc34267ea475`。
- fix 分支：`fix/review-m0-b-fix1-zip-leaf-download-r1`。
- 独立 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-b-fix1-zip-leaf-download-r1`。
- 输入 B 证据分支：`validation/review-m0-r4-b-r1-01a0583c`；证据提交：`b039f418f049aa4a7f037ba6bb68757de118f444`；runner 提交：`0e32282f9c1b3681c91427bd501a07f53dee5fd0`。
- `A` 仍为 `A_PASSED`；`B` 仍为 `B_FAILED`；`C` 仍为 `C_NOT_AUTHORIZED`。本任务没有运行或宣称正式 B 通过，没有启动 C/D，没有合并、部署或移动 `milestone/review-m0`。

## 2. 最小复现与根因

正式 B 的第 141 个断言记录为 HTTP `200` 且 ZIP 单叶逐字节比较为 false。runner 在响应体读取失败时会把比较输入收敛为空字节并保留读取错误布尔值，但既有 handoff 没有保存该布尔字段，因此单靠旧证据不能把“空响应”和“读取失败后为空”再细分。

本任务用两条确定性产品回归完成分类：

1. 生产 `HttpOwnedFileByteSource -> VerifiedReviewM0FileAccess -> ZipArchiveEntryOpener` 链在一个含两个可区分 PDF 叶和一个其他格式叶的 ZIP 上，按稳定 entry locator 与 digest 读取所选第二个 PDF；结果为：类型正确、长度正确、`selectedBytesEqual=true`、`firstLeafEqual=false`、`wholeZipEqual=false`。这排除了整个 ZIP、首叶/错误叶、二进制编码转换和 locator 映射错误。
2. 原实现由 `MaterialDownloadService` 把 `ZipArchiveEntryOpener.TargetEntryInputStream` 直接交给异步 HTTP controller，并把长度设为未知。目标叶到达 EOF 后，底层才验证目标摘要、扫描剩余归档、完成压缩安全限制检查、把 file-service 源流读到 EOF，并执行源摘要与下载后不可变元数据校验。此时 HTTP `200` 已经提交；任何后置校验失败只能表现为响应体读取失败/空比较，而不能再映射为冻结的 `503`。

真实根因因此是 **ZIP 叶完整性和整包 fail-closed 校验发生在 HTTP 响应提交之后**，不是所选 material/entry 错误。

## 3. 最小产品修复

只修改 `MaterialDownloadService` 的 ZIP 叶分支：

1. 继续通过 owner-aware `ReviewM0FileAccess.openZipEntry(owner,fileId,normalizedEntryPath,contentDigest)` 获取唯一目标；没有新增数据库、MinIO 或鉴权旁路。
2. 在构造 `DownloadPayload` 前完整消费并关闭这个有界、摘要绑定的流。只有目标摘要、剩余归档安全扫描、源摘要和下载后不可变元数据都成功，才暴露只含所选叶的内存流。
3. EOF/关闭阶段 `IOException` 在 HTTP 提交前映射为冻结 `REVIEW_M0_FILE_SERVICE_UNAVAILABLE`；不再产生“200 后失败”。
4. ZIP 叶 `Content-Length` 改为已验证叶的实际长度。普通文件路径和普通文件长度逻辑未改。

该实现仍受生产 `ArchiveSafetyLimits` 的 entry、总解压量、压缩比、条目数、路径深度和处理时限约束。默认单叶上限为 64 MiB；本修复只在该既有上限内缓存所选单叶，不写临时 payload，因此没有磁盘 payload 残留，流由 `try-with-resources` 在响应暴露前关闭。

## 4. 冻结不变量

- ZIP 顶层仍只是容器；材料仍由 `materialId -> normalizedEntryPath + contentDigest` 唯一定位。
- 不读取首个同后缀叶、不读取错误叶，也不把整个 ZIP 作为下载体。
- ZIP 路径穿越、重复/歧义条目、缺失 locator、压缩炸弹、大小/读取上限和异常映射继续 fail closed。
- 普通 PDF 仍逐字节返回原文件；`submissionFileName`、`Content-Disposition`、报告不可变、CAS 和预提交完整性契约未改。
- owner、跨用户/不存在 404、JWT 401、file-service/存储 503、`LOCAL_ONLY` 均未改。
- 没有修改冻结文档、冻结 skip、A/B runner、既有证据、`docs/PROGRESS.md` 或公共产品范围。

## 5. 修复前/后证据

### 5.1 新增最小回归

修复前临时只撤回产品改动，执行：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am `
  '-Dtest=MaterialLifecycleServiceTest#zipDownloadValidationFailureIsRejectedAndClosedBeforePayloadIsExposed' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`1 run / 1 failure / 0 errors / 0 skipped`。失败事实为 `openOwned` 未抛出冻结异常，证明旧实现会先暴露 payload。

恢复修复并执行同一 selector：`1 / 1 / 0 / 0 / 0`，`BUILD SUCCESS`。

新增/加强的精确组合：

```powershell
.\mvnw.cmd -pl ai-service -am `
  '-Dtest=MaterialLifecycleServiceTest#ordinaryAndZipDownloadsReturnOnlyTargetBytesWithCurrentUnicodeName+zipDownloadValidationFailureIsRejectedAndClosedBeforePayloadIsExposed,HttpOwnedFileByteSourceTest#productionAdapterReturnsTheExactDigestBoundZipLeaf' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`3 / 3 / 0 / 0 / 0`。

HTTP 普通/ZIP 下载精确 selector：

```powershell
.\mvnw.cmd -pl ai-service -am `
  '-Dtest=ReviewM0ApiContractTest#ordinaryUnicodeDownloadPreservesBytesMetadataAndOwnerIsolation+zipDownloadReturnsOnlyTargetLeafAndUnsupportedTopLevelIs415' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`2 / 2 / 0 / 0 / 0`；ZIP 响应长度等于实际叶长度。

### 5.2 M0-04 / M0-06 与 owner-aware 生产链

| 选择器 | 结果 |
|---|---:|
| `archive.*Test,file.*Test,material.*Test`（M0-04） | `85 / 85 / 0 / 0 / 0` |
| `api.*Test,config.*Test`（M0-06） | `38 / 38 / 0 / 0 / 0` |
| `CurrentFileServiceBaselineTest,HttpOwnedFileByteSourceTest,VerifiedReviewM0FileAccessTest,ReviewM0ConfigurationTest` | `20 / 20 / 0 / 0 / 0` |
| `FileApplicationTests,OwnerAwareFilePrecommitIntegrityHttpTest,OwnerAwareFileSecurityTest,OwnerAwareFileServiceTest,OwnedFileDownloadStagingTest,OwnerAwareJwtCompatibilityR1Test,SensitiveLoggingR1Test`（F3/F4 superset） | `28 / 28 / 0 / 0 / 0` |
| `ReviewM0IntegratedEvidenceAdapter` | `15 / 15 / 0 / 0 / 0` |

Integrated adapter 的安全结构检查：`targetCount=15`、`allPassed=true`、`p0CounterCount=15`、`allP0Zero=true`。

## 6. 构建与静态检查

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests compile
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -Dmaven.test.skip=true package
```

结果：`common`、`file-service`、`ai-service` 均 `BUILD SUCCESS`；file-service 与 ai-service fat Jar 均成功生成。

敏感信息门使用 Integrated evidence 和冻结的两个生产静态根执行，扫描 `1222` 个 Git 可见文本文件，`0` 命中。`git diff --check` 通过。

## 7. 未运行范围与剩余风险

未运行正式 B1-B8 全矩阵、完整 A、真实 document/OCR、远程模型、UI/浏览器，也未启动 C/D。这些仍由新候选的探针和完整 B 决定；本专项结果不能替代 B。

剩余操作风险是并发 ZIP 下载会为每个请求缓存一个受既有 entry 上限约束的已验证叶，相比原异步流式路径增加有界堆占用。它避免临时明文文件和提交后失败，但并发容量仍应由新候选探针/完整 B 观察。本任务没有扩大任何 ZIP 上限。

## 8. 相对固定基线的文件清单

- `ai-service/src/main/java/com/javaee/aiservice/review/m0/material/MaterialDownloadService.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiContractTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/file/HttpOwnedFileByteSourceTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/material/MaterialLifecycleServiceTest.java`
- `docs/review-m0/handoffs/B-FIX1-ZIP-LEAF-DOWNLOAD-R1.md`
