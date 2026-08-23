# Review M0 R3 独立最终自动门交接

## 1. 结论

- 状态：`FINAL_AUTO_GATE_PASSED`
- 自动状态：`AUTO_VERIFIED`
- 人工状态：`MANUAL_PENDING`
- 候选提交：`a9c51c46687dd7800ee588a606eb31c6586800e6`
- 终验分支：`gate/review-m0-r3-final-01a02`
- 终验工作树：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-r3-final-01a02`
- 最终纯文档提交：由最终交付中的完整 SHA 与 `refs/heads/milestone/review-m0` 共同给出；Git 提交不能在自身内容中稳定记录自身 SHA。
- 里程碑：全部门禁通过后，以非强制方式新建 `milestone/review-m0` 并指向上述纯文档提交；最终 ref/SHA 由交付回报给出。
- 未合并 `main`，未部署，未执行人工验收；未修改生产代码、生产资源/迁移、测试逻辑、selector、schema、脚本、断言、POM、公共配置、UI 或路由。

本轮从候选 SHA 独立创建唯一 worktree，未读取或复用 R2 的 Surefire/report/evidence 作为通过依据。所有验收结果均来自本 worktree 的新运行；运行前后均只清理本 worktree 内经绝对路径校验的生成目录。

## 2. Git、拓扑与范围审计

### 2.1 候选身份

| 项目 | 独立核对结果 |
| --- | --- |
| commit | `a9c51c46687dd7800ee588a606eb31c6586800e6` |
| parent | `c2fdd3f7bb8db8c48b38a2731eae8636f84aab05` |
| tree | `6a4246d1159f23d9c7e8c5348b7b6f3a4435b103` |
| subject | `test(review-m0): assemble R2 integration candidate` |
| 预期一父链 | `eb400ee3c9c5ee06afc82e600a5e673a4c4d88a9 -> 9d4c171d76b16b40a129507b559938b9bc9cbfee -> d27a4a0ae7367c0f79865574b6465d0ebed20c76 -> bf7da36ae23d2b25cc563ad1737867159c70f3d7 -> c2fdd3f7bb8db8c48b38a2731eae8636f84aab05 -> a9c51c46687dd7800ee588a606eb31c6586800e6` |

四项来源成果不是凭 handoff 计数接受，而是逐项比较 stable patch-id：

| 来源提交 | 链内提交 | stable patch-id |
| --- | --- | --- |
| `034a044...` | `9d4c171...` | `c955d1d9c419d54b4fabcf73b4ef5b3359136777` |
| `adc2e5c...` | `d27a4a0...` | `523a43beed539be3ac51c6c8429fdaaa508b4df2` |
| `a9f4f58...` | `bf7da36...` | `ae8e8c662d2f7f9131a0ec58334dbb28ca2e443a` |
| `76e3de0...` | `c2fdd3f...` | `3c50281298bc9d9f9b868c8a7df0dbd6987e6dc9` |

`milestone/mcp-search`（`99ed70c6b88086c9d04692ba69ee33d7b4373db3`）、`milestone/qdrant`（`00548c8445c21414f19a655370664380c9a77380`）和 `milestone/memory-v1`（`d289cf03fe57e81f78cab9ece9e2e47d84bf1474`）均以 `merge-base --is-ancestor` 独立确认是候选祖先。开始时 `refs/heads/milestone/review-m0` 不存在；创建前必须再次检查且禁止 force。

候选自身只改验收 adapter、file-service 基线测试、selector、验收脚本和 R2 handoff，无 `src/main` 变化。R3 最终 diff 只允许本 handoff 与 `docs/PROGRESS.md`。

### 2.2 结构门

- `ReviewM0Controller` 根路径为 `/api/review-m0`，方法/path 精确 30 个，无别名或越界端点。
- 未发现错别字、漏填、资格、跨材料一致性等 M1 结论进入 Review M0。
- selector 状态精确为 `READY`；生产 adapter 唯一；`M0-P0-001..015` 无缺失、重复或额外目标；`staticScanRoots` 均为仓库内相对路径，无绝对路径或 `..`。
- Review V001/V002/V003 与 file-service V002 顺序正确；H2/MySQL-mode 冷启动、重复启动、V002 中断恢复和 strict FK 校验测试通过。历史 owner-null 均 fail closed，没有猜测回填。
- `submissionFileName` 是逻辑展示名；原始文件名、objectKey、content、hash 和 bytes 的不变性由 adapter 与 owner-aware 专项验证。
- 产品边界保持：PDF/DOCX/XLSX/JPG/PNG/ZIP，ZIP 仅容器，P-08 `LOCAL_ONLY`，上传未确认不得 `MISSING`，`CANNOT_CONFIRM != FAILED`，报告不可变且旧报告 `OUTDATED`，owner-null fail closed。

## 3. 独立自动门结果

计数顺序均为 `discovered / passed / failures / errors / skipped`。

### 3.1 Review 统一验收与 R1 专项

| 门 | 实际结果 | exit/status |
| --- | --- | --- |
| 默认 `run-m0-acceptance.cmd` | `26 / 26 / 0 / 0 / 0` | exit 0；`ASSET_VERIFIED`；未冒充生产验收 |
| `run-m0-acceptance.cmd -Integrated` | `41 / 41 / 0 / 0 / 0`，其中资产 26、冻结生产目标 15 | exit 0；`AUTO_VERIFIED / MANUAL_PENDING`；`readinessMissing=[]` |
| 单独 adapter | `15 / 15 / 0 / 0 / 0` | exit 0 |
| R1-A 幂等 snapshot/schema | `8 / 8 / 0 / 0 / 0` | exit 0 |
| R1-B cancel recovery | `5 / 5 / 0 / 0 / 0` | exit 0 |
| R1-C ai owner-aware | `14 / 14 / 0 / 0 / 0` | exit 0 |
| R1-C file owner-aware | `11 / 11 / 0 / 0 / 0` | exit 0 |

Integrated evidence 的 15 个 target 均为 `PASS`；断言数依次为 `2,2,2,5,5,2,2,6,7,3,2,2,2,2,11`。15 个冻结 P0 counter 全为 0，覆盖：首次幂等响应资源变化且实例重启后不变；`CANCEL_REQUESTED` 重启收敛且 handler 不执行；same-owner 成功、cross-owner/owner-null/伪造 header fail closed，原件保持不变。report/evidence schema 均通过验收测试且 JSON 可解析。

### 3.2 Review M0 冻结回归

| 套件 | 实际结果 |
| --- | --- |
| M0-01 + Agent/Conversation 冻结选择器 | `100 / 87 / 0 / 0 / 13 expected` |
| M0-02 | `71 / 71 / 0 / 0 / 0` |
| M0-02 FIX1 精确 23 方法 | `23 / 23 / 0 / 0 / 0` |
| M0-03 | `21 / 21 / 0 / 0 / 0` |
| M0-04 | `81 / 81 / 0 / 0 / 0` |
| M0-05 | `50 / 50 / 0 / 0 / 0` |
| M0-06 | `23 / 23 / 0 / 0 / 0` |
| Wave 2 | `6 / 6 / 0 / 0 / 0` |
| Wave 3 | `23 / 23 / 0 / 0 / 0` |
| Wave 3 recheck | `13 / 13 / 0 / 0 / 0` |
| document-service 旧回归 | `4 / 4 / 0 / 0 / 0`；mock，不代表真实 OCR/网络 |
| `AllModelsTest` 应用上下文 | `1 / 1 / 0 / 0 / 0`；四个远程模型禁用，真实成功 `0/4` |
| file-service + ai-service compile | `BUILD SUCCESS` |

M0-01 的 13 个 expected skip 均从本轮新生成 Surefire XML 读取，不是笼统全局跳过：

1. `realJpgOcrAndScannedDocumentAccuracyRemainUnverified`：需要原生 Tesseract 与 `eng/chi_sim`，合成 JPG 不是真实扫描件。
2. `realPngOcrAndConfidenceRemainUnverified`：需要原生 Tesseract 与 `eng/chi_sim`，合成 PNG 不是真实扫描件。
3. `realMysql8StartupAndMigrationRemainUnverified`：真实 MySQL 8 启动和迁移待外部验证。
4. `ordinaryFileReadIsStreamingAndBounded`：冻结的 M0-04 pending 边界。
5. `docxExtractionRetainsOrderAndFrozenSourceLocatorEvidence`：冻结的 M0-03 parser pending 边界。
6. `imageExtractionHasReliableLocatorAndTypedOcrFailureSemantics`：冻结的 M0-03 OCR pending 边界。
7. `xlsxExtractionProvidesSheetAndCellRangeEvidence`：冻结的 M0-03 XLSX locator pending 边界。
8. `ownedFileSnapshotProvidesConsistentOriginalNameBytesSizeAndSha256`：冻结的 M0-04 snapshot pending 边界。
9. `reviewM0ApiUsesTheFrozenFourFieldErrorEnvelope`：冻结的 M0-06 error envelope pending 边界。
10. `reviewM0SchemaUsesAnIsolatedVersionedJdbcMigration`：冻结的 M0-02 migration pending 边界。
11. `parserFailuresMapToFrozenCannotConfirmOrSystemFailureSemantics`：冻结的 M0-03 typed failure pending 边界。
12. `ownedFileLookupRejectsAnonymousAndCrossUserAccess`：冻结的 M0-04 owner lookup pending 边界。
13. `pdfExtractionRetainsFrozenSourceLocatorEvidence`：冻结的 M0-03 PDF locator pending 边界。

这些 skip 只属于 M0-01 历史/外部边界；Review Integrated、R1、M0-02..06 新增专项、Wave 2/3、Memory、MCP 与 QV04 本轮均无 skip。

### 3.3 OmniDoc 旧能力门

| 门 | 实际结果 | 外部边界 |
| --- | --- | --- |
| Agent / Conversation | 包含在 M0-01 精确冻结选择器，新增失败/错误为 0 | synthetic 负向日志不代表远程能力 |
| Document | `DocumentServiceImplTest` 4/4 | mock；真实 OCR/网络 `NOT_RUN` |
| File | M0-01 file characterization + R1-C file 11/11 | 真实 MinIO `NOT_RUN` |
| Memory V1 Integrated | 资产 16/16；精确 Java/回归 129/129；总计 187/187，0 failure/error/skip；异步 2/2，Web build 成功 | `AUTO_VERIFIED / MANUAL_PENDING`；真实 MySQL/Redis/模型/浏览器未运行 |
| MCP Search Integrated | `80 / 80 / 0 / 0 / 0` | 真实 Tavily 未运行 |
| Qdrant/QV04 asset | `112 / 112 / 0 / 0 / 0` | `ASSET_VERIFIED`；只用确定性 SHA-256 向量 fixture；真实 Qdrant、远程 Embedding 未运行 |

`npm ci` 为 Memory 前端门准备依赖时成功，但 npm audit 报告现有依赖树有 12 项漏洞（3 moderate、7 high、2 critical）；本轮没有改 package/lockfile，也没有用 audit 结果冒充产品安全验证。

## 4. 实际命令

以下命令均从本 handoff 第 1 节的独立 worktree 根执行。为控制 JVM 资源，Review 精确套件统一使用冻结参数：

```powershell
$env:MAVEN_OPTS='-Xms16m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off'
function Invoke-ReviewSelector([string]$Selector) {
  & .\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-DargLine=-Xms32m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off' "-Dtest=$Selector" test
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

统一入口与 adapter：

```powershell
.\scripts\review-m0\run-m0-acceptance.cmd -ReportDirectory ai-service\target\review-m0-r3-default
.\scripts\review-m0\run-m0-acceptance.cmd -Integrated -ReportDirectory ai-service\target\review-m0-r3-integrated
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service '-Dtest=ReviewM0IntegratedEvidenceAdapter' '-Dreview.m0.acceptance.evidence.path=target/review-m0-r3-adapter/review-m0-integrated-evidence.json' test
```

R1 与 M0/Wave 选择器：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service '-Dtest=ReviewM0ApiIdempotencySnapshotTest,ReviewM0IdempotencySnapshotSchemaInitializerTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service '-Dtest=ReviewM0CancellationRecoveryTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am '-Dtest=HttpOwnedFileByteSourceTest,VerifiedReviewM0FileAccessTest,ReviewM0ConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -am '-Dtest=FileApplicationTests,OwnerAwareFileServiceTest,OwnerAwareFileSecurityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Invoke-ReviewSelector 'com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test'
Invoke-ReviewSelector 'com.javaee.aiservice.review.m0.checklist.*Test,com.javaee.aiservice.review.m0.privacy.*Test,com.javaee.aiservice.review.m0.extraction.*Test'
Invoke-ReviewSelector 'com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test'
Invoke-ReviewSelector 'com.javaee.aiservice.review.m0.review.*Test,com.javaee.aiservice.review.m0.report.*Test,com.javaee.aiservice.review.m0.evidence.*Test'
Invoke-ReviewSelector 'com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test'
Invoke-ReviewSelector 'com.javaee.aiservice.review.m0.integration.wave2.*Test'
Invoke-ReviewSelector 'com.javaee.aiservice.review.m0.integration.wave3.*Test'
Invoke-ReviewSelector 'com.javaee.aiservice.review.m0.integration.wave3recheck.*Test'
.\mvnw.cmd -q -pl document-service -am dependency:build-classpath '-Dmdep.outputFile=target/m0-pb-runtime-classpath.txt' '-Dmdep.includeScope=runtime'
Invoke-ReviewSelector 'com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest'
```

FIX1 的实际 23-method selector：

```powershell
$fix1Selector = 'ReviewM0JobSubmissionServiceTest#crossOwnerCaseSubmissionIsNotFoundAndLeavesNoJobOrIdempotencySideEffect+concurrentCrossOwnerSubmissionsAllFailWithoutCreatingProbeableState,' +
  'JdbcReviewReportStoreTest#crossCaseAndOwnerPublicationRollsBackReportJobPointerAndIdempotencyState+reportInputFingerprintMustEqualTheLeasedJobAndFailureHasNoPublicationSideEffect+succeededJobCannotBeIdempotentlyReusedToProbeAnotherCaseReport,' +
  'JdbcReviewM0RemoteCallStoreTest#confirmedPayloadDigestMustMatchActualSendBytes+crossOwnerAndCaseConsentIsHiddenAndLeavesConsentAndCallStateUntouched+mismatchedConsentOwnerOnTheSameCaseCannotAuthorizeSendIntent+oldBasisRevisionAndInvalidatedPreviewCannotAuthorizeSendIntent+operationMismatchCannotAuthorizeSendIntentEvenWithTheSameDigest+consumedConsentWithoutItsMatchingRemoteCallCannotBeConsumedAgain+concurrentConsentConsumptionAllowsExactlyOneLogicalJob,' +
  'ReviewM0ModelContractTest#zipRemainsAFormalInputContainerButCannotBecomeAMaterialUnit,' +
  'ReviewM0DdlConstraintTest#ordinarySourceHasOneTopLevelMaterialWhileZipAllowsDistinctLeavesOnly+zipSourceIsSupportedButDatabaseRejectsTheContainerAsAMaterialUnit,' +
  'ReviewM0SchemaInitializerTest#initializesAllFrozenTablesAndRepeatedInitializationIsIdempotent+resumesVersionTwoAfterAInterruptedCyclicReferenceMigration+strictValidationRejectsEveryMissingV002ForeignKey+strictValidationUsesForeignKeyStructureRatherThanOnlyConstraintName+strictValidationRejectsCascadingRulesOnV002ForeignKeys'
Invoke-ReviewSelector $fix1Selector
```

旧能力、Memory、MCP、QV04 与编译：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl document-service -DforkCount=1 '-Dtest=DocumentServiceImplTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=AllModelsTest' '-Dai.models.openai[qwen3.7-max-2026-05-20].enabled=false' '-Dai.models.openai[glm-5].enabled=false' '-Dai.models.openai[kimi-k2.5].enabled=false' '-Dai.models.openai[MiniMax-M2.5].enabled=false' '-Dspring.rabbitmq.listener.simple.auto-startup=false' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests compile
Push-Location web; npm.cmd ci; Pop-Location
.\scripts\memory\run-m07-acceptance.cmd -Integrated -ReportDirectory ai-service\target\review-m0-r3-memory
.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated
.\scripts\qdrant\run-qv04-acceptance.cmd -ReportDirectory ai-service\target\review-m0-r3-qdrant
```

安全、格式与清理：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& 'scripts/review-m0/assert-no-sensitive-data.ps1' -RepositoryRoot '<worktree>' -ReportPath '<report>' -EvidencePath '<evidence>' -StaticScanRoots @('ai-service/src/main/java/com/javaee/aiservice/review/m0','file-service/src/main/java/com/javaee/fileservice')"
# 22 个 Git JSON + 8 个机器 JSON：UTF-8 BOM 兼容 JSON.parse，30/30，0 error
# git ls-files '*.ps1'：21 个文件经 PowerShell Parser，0 error
git diff --check
.\mvnw.cmd -s .mvn\settings-local.xml clean -DskipTests
git clean -fdX -- ai-service/target common/target document-service/target file-service/target target web/dist web/node_modules
```

## 5. 安全与仓库结论

- 候选上的两次独立 machine-artifact 敏感扫描均扫描 1176 个 Git 可见文本文件，并同时检查 Review/file-service 生产根及当前 report/evidence；0 secret、fixture PII 或禁用 JSON 属性匹配。
- 8 个当前机器 artifact 文件另做 key、JWT/Bearer、预签名 URL、PII、私钥/AWS key 模式扫描，0 匹配。
- 22 个 Git JSON 与 8 个当前机器 JSON 共 30 个均可解析；21 个 Git PowerShell 文件 parser error 为 0。
- 默认/Integrated report 与 evidence schema 由精确资产测试验证；15-target evidence 均为 PASS。
- `git diff --check` 通过。运行生成的 Surefire、报告、evidence、日志、Maven target、`web/dist` 与 `web/node_modules` 均在提交前从本 worktree 清除，不进入 Git。
- 最终文档内容在提交前已再次扫描 1177 个 Git 可见/新增文本文件，并复核生产根、22 个 Git JSON、21 个 PowerShell 文件与 `git diff --check`，均为 0 error/match。提交后还需对最终 HEAD 重复 `git diff --check HEAD^ HEAD`、敏感扫描、parser 和 clean-status 复核；结果由最终交付回报。任何失败都禁止创建里程碑。

## 6. 外部与人工边界

以下均为 `NOT_RUN`，不能从本轮自动门推导为可用：

| 边界 | 状态 |
| --- | --- |
| 真实 MySQL 8：Review V001/V002/V003、file-service V002 冷启动与升级 | `NOT_RUN` |
| 真实 MinIO 与真实 owner-aware 文件对象 | `NOT_RUN` |
| ai-service + file-service 双服务进程 | `NOT_RUN` |
| 真实 JWT、多用户和伪造请求的网络链 | `NOT_RUN` |
| document/OCR 网络链、原生 OCR 与真实扫描件 | `NOT_RUN` |
| 远程模型 | `NOT_RUN`；应用上下文中四模型成功 `0/4` |
| 真实 Tavily MCP | `NOT_RUN` |
| 浏览器/页面与 P-09 人工清单 | `NOT_RUN / PENDING` |
| 真实 Qdrant 与远程 Embedding | `NOT_RUN`；仅 QV04 asset |
| 部署、生产容量/TLS/HA/备份/灾备 | `NOT_RUN` |

P-08 保持 `LOCAL_ONLY`。P-09 保持 `NOT_RUN / PENDING`。最终状态只允许 `AUTO_VERIFIED / MANUAL_PENDING`，不得写成 `HUMAN_ACCEPTED`。

## 7. 最小责任边界

R3 是独立终验，不承担功能修复。若最终纯文档 HEAD 的 diff、安全扫描、parser、clean-status 或非强制 ref 创建发生失败，应将状态改为 `GATE_FAILED`，不得修改生产/测试代码，也不得创建或移动 `milestone/review-m0`；协调者应另开最小修复任务。
