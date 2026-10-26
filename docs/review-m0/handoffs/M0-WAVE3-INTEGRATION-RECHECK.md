# Review M0 Wave 3 FIX1 独立复核交接

## 1. 结论

**状态：`READY_FOR_M0_06`。**

复核日期：2026-08-21（Asia/Shanghai）。

M0-05 FIX1 已真正关闭原 Wave 3 的 B-01、B-02、B-03。原 Wave 3 测试原样 23/23 通过；M0-05 自有测试为原 44 项加 FIX1 新增 6 项，共 50/50；本次另增 13 项独立对抗测试并全部通过。全部指定前序回归、模块检查和默认验收均通过。

本文件所在的唯一复核提交是 M0-06 的允许起始点。该结论只表示可以开始 M0-06，不表示最终集成完成，也不表示 `AUTO_ACCEPTED` 或人工验收完成。Integrated 验收仍按冻结规则报告 `PENDING_INTEGRATION` 并原生退出 2。本次没有开发 M0-06。

## 2. 固定起点、提交拓扑与隔离

- 固定复核起点：`821b398d23171e6945ec2787c80c2a0630407bf3`
- 隔离分支：`review/m0-wave3-recheck-01a02473`
- 隔离 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-wave3-recheck-01a02473`
- 最终交付：固定起点之上的单一复核提交；最终 `HEAD` 即 M0-06 起始 SHA。

已核对线性提交链及相邻 `merge-base`：

```text
c0ba660568651bc5dbc004e35bd54798e5469768
  -> 2f8cb93d82a0ef9eb914e849f89ae013dd1b7a6d  M0-05
  -> 3638fb0fb8e97c269a94a782b09af5f97b97c67d  原 Wave 3 失败门与测试
  -> 821b398d23171e6945ec2787c80c2a0630407bf3  M0-05 FIX1 / 本复核起点
  -> 本文件所在唯一复核提交                        M0-06 起始 SHA
```

`821b398d...` 的唯一父节点是 `3638fb0...`，`3638fb0..821b398d` 只有一个提交。复核从该 commit 原样创建 worktree，没有修改当前 checkout 或其他 worktree。

## 3. 来源、修复范围与零改写证明

FIX1 相对 `3638fb0...` 只改动 7 个文件：

```text
ai-service/src/main/java/com/javaee/aiservice/review/m0/review/DeterministicReviewEngine.java
ai-service/src/main/java/com/javaee/aiservice/review/m0/review/FormalReviewSubmissionService.java
ai-service/src/main/java/com/javaee/aiservice/review/m0/review/IncrementalPlan.java
ai-service/src/main/java/com/javaee/aiservice/review/m0/review/IncrementalReviewValidator.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/review/FormalReviewIdempotencyRegressionTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/review/IncrementalReviewRenameRegressionTest.java
docs/review-m0/handoffs/M0-05-FIX1.md
```

范围核对结果：

- 原 Wave 3 测试目录在 `3638fb0...` 与 `821b398d...` 的 Git tree 均为 `98e502f0e39d3bd2dac812e4a98637c6e73e6966`，目录内容零 diff。
- 原 M0-05 的 8 个测试/fixture 文件在 `2f8cb93...` 与 `821b398d...` 的 blob 全部相同，`ORIGINAL_TEST_BLOB_DIFFS=0`。原 44 项仍全部执行并通过；50 项由原 44 项与 FIX1 新增 6 项组成，没有删除测试或弱化原断言。
- FIX1 没有修改 M0-02/03/04 所有权目录、POM/YAML、Agent、API/Controller、前端、冻结契约、M0-07、acceptance、`scripts/review-m0/**` 或 `docs/PROGRESS.md`。
- 本次复核提交只新增两个 `wave3recheck` 测试文件和本交接；没有修改任何生产代码、M0-05 自有测试、原 Wave 3 测试、来源 handoff 或冻结资产。
- 禁止依赖/绕过扫描和敏感信息扫描无命中；`git diff --check` 通过。

## 4. B-01 / B-02 / B-03 关闭结论

### B-01：关闭

合法同扩展名逻辑改名后，changed material 的所有文件名相关 finding 都被移除并按当前 manifest 重建，而不是只更新第一项。独立测试同时覆盖同一材料的 `CANNOT_CONFIRM` 与多条命名 finding；证据只含当前 `submissionFileName`。未受影响材料的规范 finding/evidence 保持原实例复用，证明实现仍按最小影响集合运行，而非无条件完整重审。

### B-02：关闭

扩展名与正式类型兼容性发生变化时，`IncrementalPlan.recomputeCompleteness` 扩大影响集合；增量与完整审查的 findings、evidence、计数和 assessment 深度等价。PDF 到 DOCX 的防御性输入均产生 `CANNOT_CONFIRM/TYPE_MISMATCH`，整份 `MISSING=0`。`.pdf -> .PDF` 按冻结的大小写规则仍走最小 name-only 路径，不扩大为全量 completeness 重算。

正常 M0-04 改名写路径继续拒绝真实扩展名改变；FIX1 只为迁移、适配器或历史 manifest 做防御性等价处理，没有放宽 M0-04 约束。

### B-03：关闭

客户端规范 HTTP 请求摘要与首次执行 envelope 已分离。相同 owner/case/endpoint/body/key 在多次逻辑名修订后始终回放首次 Job、首次 envelope 和首次 `inputFingerprint`；回放不重新捕获当前 manifest，且不新增 Job、idempotency 或 report 数据。相同 key 不同 body 仍冲突；不同 key 捕获当前 manifest 并形成新 fingerprint。

owner、case 和 endpoint 任一不同均不能回放原资源。并发同 key/body 只创建一个 Job；与真实 `renameOwned` 并发时，两条路径按 case 锁顺序串行成功，结果只能对应冻结事务顺序，没有冲突或半写。首次前置门失败不占用 key；首次成功后，即使当前 provider 状态失效，仍能回放首次成功资源。

FIX1 没有修改 M0-02 Store/SPI，也没有削弱同 key 不同载荷冲突、owner/case 作用域、资源所有权检查、租约或发布事务语义。Job 的 `request_json`、Job request digest 与 `inputFingerprint` 仍保存首次不可变服务器快照；规范客户端幂等摘要不含当前服务器 manifest。

## 5. 新增独立对抗测试

仅新增：

```text
ai-service/src/test/java/com/javaee/aiservice/review/m0/integration/wave3recheck/Wave3RecheckIncrementalAdversarialTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/integration/wave3recheck/Wave3RecheckIdempotencyAndIntegrityTest.java
```

`Wave3RecheckIncrementalAdversarialTest` 共 5 项：

- 两个以上材料只改一个逻辑名，changed material 的全部文件名相关发现刷新，未受影响材料证据保持规范且复用；
- changed material 同时有多条命名/材料证据时全部刷新；
- 合法 PDF 到 PDF 改名保留最小影响集合；
- 扩展名大小写变化与真正扩展名变化分别走冻结路径；
- TYPE_MISMATCH 的全局 MISSING 抑制、深度等价，以及材料顺序、分配顺序和重复执行的规范稳定性。

`Wave3RecheckIdempotencyAndIntegrityTest` 共 8 项：

- 连续多次逻辑名修订后的首次 Job/envelope/fingerprint 回放与零重复捕获/零新增行；
- 相同 key 不同 body 冲突，不同 key 捕获新 manifest/fingerprint；
- owner/case/endpoint 隔离；
- 并发同 key/body 单 Job、单 idempotency；
- 前置门首次失败不占 key，首次成功后当前状态失效仍回放；
- upload 未确认、stale checklist、storage 不可用三类失败的 Job/idempotency/report/finding/evidence/case 指针零半写；
- 正式 `MaterialLifecycleService.renameOwned` 与首次提交并发时的事务顺序、单资源与零半写；
- 客户端摘要不含服务器当前 manifest，首次 Job envelope/fingerprint 保留首次快照，持久化行、异常和机器可见输出不泄露材料正文、object locator、预览或远程载荷。

独立套件结果：`13 discovered / 13 passed / 0 failure / 0 error / 0 skipped`。

## 6. 完整阶段门结果

Maven 精确套件统一使用冻结 Wave 3 的受限 JVM 配置并替换 selector：

```powershell
$env:MAVEN_OPTS='-Xms16m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-DargLine=-Xms32m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off' '-Dtest=<selector>' test
```

所有计数均为 discovered / passed / failures / errors / skipped：

| 验证项 | selector / 范围 | 结果 | 退出 |
| --- | --- | --- | --- |
| M0-05 | `review.*Test,report.*Test,evidence.*Test` 完整包名 | `50 / 50 / 0 / 0 / 0` | 0 |
| 原 Wave 3 | `com.javaee.aiservice.review.m0.integration.wave3.*Test` | `23 / 23 / 0 / 0 / 0` | 0 |
| 本次 recheck | `com.javaee.aiservice.review.m0.integration.wave3recheck.*Test` | `13 / 13 / 0 / 0 / 0` | 0 |
| M0-03 | checklist/privacy/extraction 冻结 selector | `21 / 21 / 0 / 0 / 0` | 0 |
| M0-04 | archive/file/material 冻结 selector | `76 / 76 / 0 / 0 / 0` | 0 |
| M0-02 | model/store/job/idempotency 冻结 selector | `64 / 64 / 0 / 0 / 0` | 0 |
| M0-02 FIX1 | `M0-WAVE2-INTEGRATION.md` 冻结 23 方法 selector | `23 / 23 / 0 / 0 / 0` | 0 |
| Wave 2 | `com.javaee.aiservice.review.m0.integration.wave2.*Test` | `6 / 6 / 0 / 0 / 0` | 0 |
| M0-01 | baseline + Agent/Controller/Conversation/JDBC 冻结 selector | `100 / 87 / 0 / 0 / 13 expected` | 0 |
| `DocumentServiceImplTest` | 单元测试 | `4 / 4 / 0 / 0 / 0` | 0 |
| file-service | `-DskipTests compile` | BUILD SUCCESS | 0 |
| `AllModelsTest` | synthetic、四个真实模型 disabled | `1 / 1 / 0 / 0 / 0`；真实模型 `0/4` | 0 |

M0-01 classpath 前置命令成功。`DocumentServiceImplTest` 是 mock 单元测试，file-service 仅编译，`AllModelsTest` 仅使用 `synthetic-not-real` 配置；它们均不构成真实外部服务验证。

冻结验收：

```powershell
cmd.exe /d /v:on /c "call .\scripts\review-m0\run-m0-acceptance.cmd & set gateExit=!ERRORLEVEL! & echo GATE_EXIT=!gateExit! & exit /b !gateExit!"
cmd.exe /d /v:on /c "call .\scripts\review-m0\run-m0-acceptance.cmd -Integrated & set gateExit=!ERRORLEVEL! & echo GATE_EXIT=!gateExit! & exit /b !gateExit!"
```

- 默认：`26 / 26 / 0 / 0 / 0`，敏感信息扫描无命中，`ASSET_VERIFIED`，原生退出 0。
- Integrated：资产 `26/26`，敏感信息扫描无命中，`PENDING_INTEGRATION`、`finalAcceptance=false`，原生退出 2。
- Integrated `readinessMissing`：`REVIEW_M0_PRODUCTION_ADAPTER_NOT_DECLARED`、`REVIEW_M0_EXACT_INTEGRATED_TEST_SELECTION_NOT_DECLARED`、`REVIEW_M0_STATIC_SCAN_ROOTS_NOT_DECLARED`。
- Integrated `unverified`：`REVIEW_M0_PRODUCTION_BEHAVIOR_NOT_EXECUTED`、`INTEGRATION_ADAPTER_NOT_READY`。

## 7. 数据完整性与敏感信息

独立持久化测试覆盖 upload 未确认、过期清单、存储不可用、并发同 key、改名竞态和成功后回放。所有失败路径均断言 Job、idempotency、报告、发现、证据以及 case CURRENT 指针没有半写；成功并发只产生一个规范资源。

规范客户端请求摘要只编码 owner/case/method/endpoint/body，不包含每次执行时的当前 manifest。首次 Job 的不可变 envelope、request digest 与 `inputFingerprint` 则保留首次 manifest 的审计快照。测试使用合成敏感标记验证持久化字段和异常不泄露正文、object locator、预览或远程载荷；冻结静态敏感信息门也无命中。

## 8. 外部依赖与未验证项

以下均为 `NOT_RUN`，不能由 H2 MySQL mode、mock、编译、synthetic 上下文、MinIO client 本地构造或 fail-closed 报告推断为通过：

- 真实 MySQL 8：`NOT_RUN`；
- 真实 owner-aware file-service：`NOT_RUN`；
- 真实 MinIO：`NOT_RUN`；
- 真实 document-service / OCR / 解析器网络链路：`NOT_RUN`；
- 四个真实远程模型：`NOT_RUN`，实际成功 `0/4`；
- Redis、RabbitMQ：`NOT_RUN`；
- 浏览器与前端端到端：`NOT_RUN`；
- M0-06 API/auth/Spring production adapter、最终精确 Integrated selector 和生产静态扫描根：尚未实现或声明。

## 9. 交付边界

复核提交除本文件外仅含两个 `wave3recheck` 测试文件。最终工作树必须洁净，固定起点到最终 `HEAD` 必须只有这一提交且授权范围精确。该 `HEAD` 可作为 M0-06 起始 SHA；完成本交接后停止，不在本分支开发 M0-06。
