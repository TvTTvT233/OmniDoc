# Review M0 R2 集成候选交接

## 1. 结论与边界

- 阶段结论：`CANDIDATE_READY`（只代表本交接列出的确定性自动门；人工和真实外部环境仍为 `MANUAL_PENDING / NOT_RUN`）。
- 固定集成起点：`eb400ee3c9c5ee06afc82e600a5e673a4c4d88a9`。
- 分支：`integration/review-m0-r2-candidate-01a02`。
- worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-r2-candidate-01a02`。
- R2 集成提交（最终门执行时）：`9799ebc8677d41179ed1ad9c9f0077ef41b6a5ec`；本文件最终化后以 amend 保持为同一个 R2 集成提交，最终完整 SHA 由 `git rev-parse HEAD` 和交付消息共同给出。
- 最终候选 HEAD：当前分支唯一顶端 R2 集成提交。Git 提交不能在自身树内容中包含自己的最终 SHA（内容改变会再次改变 SHA）；因此本文件记录完整的门禁执行 HEAD，最终协调者须以交付消息中的完整 SHA 及 `git rev-parse HEAD` 做无歧义核验。
- 没有建立 `milestone/review-m0`，没有合并项目 `main`，没有部署。
- 没有修改 `docs/PROGRESS.md`、公共 `application*.yml`、根 POM 或依赖。

## 2. 上游审计与集成顺序

开始前完整读取：`AGENTS.md`、`README.md`、`docs/OMNIDOC_CONTEXT.md`、`docs/requirements/SCHOLARSHIP_REVIEW_V1.md`、`docs/PROGRESS.md`、`docs/review-m0/README.md`、`docs/review-m0/00..04`、`M0-06`、`M0-07A`、`M0-07B` 以及三个 `M0-R1-*` handoff。

四个来源提交均以固定起点为直接父提交，来源 worktree 与基线 worktree 均为 clean；逐项 `git diff-tree` 的修改范围与 handoff 一致。按冻结顺序执行且没有冲突：

| 顺序 | 来源提交 | 本分支 cherry-pick 提交 | 结果 |
|---|---|---|---|
| 1 | `034a0445599a4ab57a54a33a1f60d9c0fee2aebe` | `9d4c171d76b16b40a129507b559938b9bc9cbfee` | R1-A；0 冲突；patch-id `c955d1d9...` 一致 |
| 2 | `adc2e5c4f7f9bf1461b079f536434a923796137a` | `d27a4a0ae7367c0f79865574b6465d0ebed20c76` | R1-B；0 冲突；patch-id `523a43be...` 一致 |
| 3 | `a9f4f5825ac8aefac99721c5072e3074bc43ac85` | `bf7da36ae23d2b25cc563ad1737867159c70f3d7` | R1-C；0 冲突；patch-id `ae8e8c66...` 一致 |
| 4 | `76e3de0b720025392a7e961c3bcd3ac6ad9f4770` | `c2fdd3f7bb8db8c48b38a2731eae8636f84aab05` | R1-D；0 冲突；patch-id `3c502812...` 一致 |

每一步均执行 `git status --short --branch`、`git log --oneline --decorate -n 6`、`git diff-tree --no-commit-id --name-status -r HEAD`、`git diff --check HEAD^ HEAD` 与 ancestry 核对；没有丢失文件。四个原提交因 cherry-pick 保留等价 patch ancestry，来源 SHA/父关系与本分支对应提交同时可审计。

## 3. R2 机械适配

只做组合装配，没有新增产品能力：

1. `ReviewM0IntegratedEvidenceAdapter` 对齐 R1-A 的 immutable response API：传入冻结响应类型，重放不再读取变化后的资源；仍真实证明资源变化和进程重建后首次 status/body 不变。
2. 取消适配改为通过新 `ReviewM0JobDispatcher.start()` 触发 R1-B 的启动收敛，并委托专项生产测试证明 `CANCEL_REQUESTED -> CANCELLED`、handler/report/remote side effect 均未调用；没有用 `runNext` 绕过启动路径。
3. selector 在 PENDING 阶段 15/15 全通过后才由 `PENDING_INTEGRATION` 改为 `READY`，只清空 `pendingReasons`；15 个精确目标、FQCN、method、`staticScanRoots` 均未删除、跳过或弱化。
4. PowerShell 5.1 对单一 adapter / 单一 Surefire suite 返回 scalar；验收脚本只增加数组包装，未改变目标或断言。
5. M0-01 的 file-service 源码 characterization 机械更新为“旧上传路径保持不变，同时 owner-aware 路径新增 owner/SHA-256/version/signature”，以兼容 R1-C；未放宽 owner-null fail-closed、跨 owner/不存在统一 404 或原件不变契约。

仍保持冻结设计：M0 只做齐全性和文件命名；`submissionFileName` 不修改原对象；叶子格式 PDF/DOCX/XLSX/JPG/PNG，ZIP 只作容器；`LOCAL_ONLY`；owner-null fail closed；跨 owner 与不存在统一 404。

## 4. 阶段门 A-D

### 4.1 selector 仍为 PENDING 时的直接 adapter

命令：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service '-Dtest=ReviewM0IntegratedEvidenceAdapter' '-Dreview.m0.acceptance.evidence.path=target/review-m0-r2-stage-b/review-m0-integrated-evidence.json' test
```

最终结果：`15 discovered / 15 passed / 0 failures / 0 errors / 0 skipped`，exit `0`。JSON Schema draft 2020-12 校验通过；`M0-P0-001..015` 全部存在并为 PASS；15 个 P0 counter 全为 `0`。其中幂等目标 assertions=`6`，取消目标 assertions=`7`。

诚实记录两次集成诊断：第一次因 adapter 仍调用 R1-A 旧签名而 testCompile 2 errors；机械适配后为 15/14/1，唯一失败是 adapter 用 `runNext` 没有触发 R1-B 启动恢复。改为新 dispatcher 的 `start()` 并加入生产专项委托后达到上述 15/15。两项均为 R1-D 验收适配问题，不是通过降低生产断言解决。

### 4.2 默认 Review M0 门

```powershell
.\scripts\review-m0\run-m0-acceptance.cmd -ReportDirectory ai-service\target\review-m0-r2-default
```

结果：`ASSET_VERIFIED`；`26 / 26 / 0 / 0 / 0`；exit `0`。在 R2 集成提交上最终复跑的静态敏感扫描为 `1176` 个文本文件，0 命中。默认模式没有执行生产行为，`manualStatus=NOT_READY`、`finalAcceptance=false`。

### 4.3 Integrated Review M0 门

```powershell
.\scripts\review-m0\run-m0-acceptance.cmd -Integrated -ReportDirectory ai-service\target\review-m0-r2-integrated
```

最终结果：asset `26/26` + production `15/15` = `41 / 41 / 0 / 0 / 0`；`AUTO_VERIFIED`；`manualStatus=MANUAL_PENDING`；`readinessMissing=[]`；15 个 P0 counter 全为 `0`；在 R2 集成提交上最终复跑的静态敏感扫描为 `1176` 个文本文件，0 命中；exit `0`。最终报告的 `baselineCommit` 为 `9799ebc8677d41179ed1ad9c9f0077ef41b6a5ec`。

PowerShell 5.1 scalar 兼容适配前两次组合运行分别在 adapter 之前和 adapter 之后退出 1；二者都没有产生虚假成功报告。数组机械适配后才得到上述最终 exit 0。

## 5. 精确回归

以下计数顺序均为 `discovered / passed / failures / errors / skipped`，命令均从本 worktree 根执行。

| 范围 | 精确选择/命令摘要 | 结果 |
|---|---|---|
| R1-A | `-Dtest=ReviewM0ApiIdempotencySnapshotTest,ReviewM0IdempotencySnapshotSchemaInitializerTest` | `8 / 8 / 0 / 0 / 0` |
| R1-B | `-Dtest=ReviewM0CancellationRecoveryTest` | `5 / 5 / 0 / 0 / 0` |
| R1-C ai | `-Dtest=HttpOwnedFileByteSourceTest,VerifiedReviewM0FileAccessTest,ReviewM0ConfigurationTest` | `14 / 14 / 0 / 0 / 0` |
| R1-C file | `-pl file-service -Dtest=FileApplicationTests,OwnerAwareFileServiceTest,OwnerAwareFileSecurityTest test` | `11 / 11 / 0 / 0 / 0` |
| M0-02 | `-Dtest='com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test'` | `71 / 71 / 0 / 0 / 0` |
| M0-03 | `-Dtest='com.javaee.aiservice.review.m0.checklist.*Test,com.javaee.aiservice.review.m0.privacy.*Test,com.javaee.aiservice.review.m0.extraction.*Test'` | `21 / 21 / 0 / 0 / 0` |
| M0-04 | `-Dtest='com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test'` | `81 / 81 / 0 / 0 / 0` |
| M0-05 | `-Dtest='com.javaee.aiservice.review.m0.review.*Test,com.javaee.aiservice.review.m0.report.*Test,com.javaee.aiservice.review.m0.evidence.*Test'` | `50 / 50 / 0 / 0 / 0` |
| M0-06 | `-Dtest='com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test'` | `23 / 23 / 0 / 0 / 0` |
| Wave2 | 冻结 handoff 的精确 method selector | `6 / 6 / 0 / 0 / 0` |
| Wave3 | 冻结 handoff 的两组精确 selector | `23 / 23 / 0 / 0 / 0`（`17+6`） |
| Wave3 recheck | 冻结 recheck 的两组精确 selector | `13 / 13 / 0 / 0 / 0`（`8+5`） |
| M0-01 + Agent + Conversation | `-Dtest='com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest'` | `100 / 87 / 0 / 0 / 13`；13 项是冻结的外部/未实现边界 `@Disabled` |
| document-service | `-pl document-service -DforkCount=1 '-Dtest=DocumentServiceImplTest' test` | `4 / 4 / 0 / 0 / 0`；mock，不是真实 OCR/网络 |
| AllModels | synthetic 环境；四个 indexed model `enabled=false`；RabbitMQ listener false | `1 / 1 / 0 / 0 / 0`；真实模型成功 `0/4` |
| MCP T04 | `.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated` | `80 / 80 / 0 / 0 / 0`；敏感扫描 1175，exit 0 |
| Qdrant QV04 asset | `.\scripts\qdrant\run-qv04-acceptance.cmd -ReportDirectory ai-service\target\review-m0-r2-qdrant` | `112 / 112 / 0 / 0 / 0`；`ASSET_VERIFIED`，敏感扫描 1175，exit 0；真实 Qdrant 未执行 |
| Memory M07 | `.\scripts\memory\run-m07-acceptance.cmd -Integrated -ReportDirectory ai-service\target\review-m0-r2-memory` | 总计 `187 / 187 / 0 / 0 / 0`：asset 16，Integrated 171；`AUTO_VERIFIED / MANUAL_PENDING`，exit 0 |

所有 ai-service Maven 精确选择使用：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service "-Dtest=<上述精确选择>" test
```

M0-01 parser 测试前按冻结 handoff 用 `document-service dependency:build-classpath` 准备运行时 classpath。日志中的预期 parser/handler/KnowledgeIndex synthetic 异常是负向断言路径；Surefire failure/error 均为 0。

编译：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests compile
```

结果：OmniDoc/common/file-service/ai-service 全部 `BUILD SUCCESS`，exit `0`。

## 6. 数据库迁移与启动兼容

- Review 核心初始化器先执行/校验 `V001__review_m0_core.sql`，再执行/校验 `V002__review_m0_cyclic_references.sql`；H2 MySQL-mode 已覆盖空库、重复初始化、V002 中断恢复、错误版本、缺表和四个循环 FK 的 strict fail-fast。
- R1-A 的快照初始化器在核心之后执行 `V003__review_m0_idempotency_response_snapshot.sql`；专项 2/2 覆盖自动迁移幂等创建，以及手工迁移模式缺 V003 时 fail closed。V003 是旁路新增表，不重写 V001/V002。
- file-service 新建库由 `file.sql` 包含 owner-aware 列/约束；既有库迁移为 `db/file-service/V002__owner_aware_file_access.sql`。源码明确写明历史行 `owner_id=NULL`，不从 bucket/object key/header/event 猜测回填；旧行继续 fail closed。
- 真实 MySQL 8 的冷启动和升级执行均为 `NOT_RUN`，因此这里只确认迁移顺序、H2 MySQL-mode 和源码/契约兼容，不冒充真实数据库验收。

## 7. 静态检查与边界

- `git diff --check`：0 error。
- 最终 Review M0 扫描 1176 个文本文件且 0 命中；MCP/QV04 在 handoff 加入前分别扫描 1175 个文本文件且 0 命中；Memory 最终扫描 1176 个文本文件且 0 命中。
- selector 保留精确 FQCN、method 与 static roots；`readinessMissing=[]`。
- 没有增加端点、UI/路由、远程模型、document/OCR、Agent Tool、MCP/Qdrant/Memory 产品实现。

## 8. 真实依赖与剩余人工项

| 项目 | 状态 | 说明 |
|---|---|---|
| 真实 MySQL 8（Review V001/V002/V003、file V002 冷启动/升级） | `NOT_RUN` | 需目标库备份、变更窗口和真实迁移日志 |
| 真实 MinIO | `NOT_RUN` | 未启动真实对象存储、versioning 或双客户端可见性验证 |
| ai-service + file-service 双服务进程 | `NOT_RUN` | 本轮是进程内/MockMvc/源码契约与编译 |
| 真实 JWT | `NOT_RUN` | owner-aware MockMvc 使用测试身份，不冒充真实签发/过期/跨用户浏览器链 |
| 真实 document-service / OCR | `NOT_RUN` | 只执行既有 mock 单测；真实 PDF/DOCX/XLSX/JPG/PNG/ZIP 样本未走网络链 |
| 远程模型 | `NOT_RUN` | AllModels 明确禁用，真实成功 0/4 |
| 真实 Qdrant / remote embedding | `NOT_RUN` | QV04 仅 deterministic SHA-256 asset；Integrated 外部门未执行 |
| 浏览器/UI 人工验收 | `NOT_RUN` | `manualStatus` 必须保持 `MANUAL_PENDING`，不得写 `HUMAN_ACCEPTED` |

第三轮独立终验必须以本分支最终 HEAD 为输入，先核对第 2 节四个来源提交等价 ancestry 和第 3 节机械 diff，再从 clean worktree 重跑：默认 Review 门、Integrated Review 门、R1-A/B/C 专项、M0-02..06、Wave2/Wave3/recheck、file-service owner-aware/冻结回归、Memory/MCP/Qdrant 冻结门、两服务 compile、`git diff --check` 和敏感扫描。若要解除人工项，必须另在受控环境运行真实 MySQL 8、MinIO、双服务、真实 JWT、document/OCR、远程模型和浏览器；不得把本次 `AUTO_VERIFIED` 当作人工或外部环境验收。

## 9. 最终化记录

- R2 integration commit（最终门执行时）：`9799ebc8677d41179ed1ad9c9f0077ef41b6a5ec`。本文件最终化采用 amend，不增加第二个产品/功能提交；最终完整 SHA 见交付消息并以 `git rev-parse HEAD` 复核。
- clean-worktree Memory M07 Integrated：首次因 `web/node_modules` 尚未安装，在 Java asset `16/16`、Java backend/regression `129/129` 后以 exit 1 停在前端依赖前置检查；执行锁文件驱动的 `npm.cmd ci` 后原命令最终 exit 0。机器报告为 `187 / 187 / 0 / 0 / 0`，其中 asset 16、Integrated 171（含 M06 Frontend 42）；`AUTO_VERIFIED / MANUAL_PENDING`。依赖安装只生成 ignored 本机构建内容，没有修改受控文件。
- Review 默认/Integrated 最终复跑输入 HEAD：`9799ebc8677d41179ed1ad9c9f0077ef41b6a5ec`；分别 `ASSET_VERIFIED 26/26` 与 `AUTO_VERIFIED 41/41`，exit 均为 0。
- 最终工作树：交付前用 `git status --short` 核对 clean；`git diff --check HEAD^ HEAD` 为 0 error；四个 cherry-pick 提交及 R2 集成提交均在 ancestry 中。
