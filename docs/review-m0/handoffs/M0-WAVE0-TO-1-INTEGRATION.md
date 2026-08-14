# Review M0 Wave 0→1 基线集成复核交接

## 1. 结论与范围

- 日期：`2026-08-14`
- 状态：`M0_01_INTEGRATED / BASELINE_VERIFIED / PENDING_INTEGRATION`
- 固定起始基线：`2157ab7d530c8a65996682fc63b1016bef517d07`
- M0-01 来源提交：`5c3c623be142ef26403f9e9b550e4d691cdbf19c`
- 协调分支：`review/m0-wave1-integration-019ff70c`
- 协调 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-wave1-integration-019ff70c`
- 集成方式：`git merge --ff-only 5c3c623be142ef26403f9e9b550e4d691cdbf19c`
- 生产代码、公共 POM、公共配置、冻结契约、验收脚本/资产、Web、`docs/PROGRESS.md`：未修改
- Review M0 生产能力：未实现、未验证

本次只复核 M0-01 的来源拓扑、所有权、测试事实和 Wave 1 起点。没有开发或修复生产能力，没有把文件服务、解析、迁移或 Review API 的当前缺口写成已实现。

## 2. 来源拓扑、patch 与所有权

来源提交的唯一父提交经 `rev-list --parents --max-count=1` 核对为：

```text
5c3c623be142ef26403f9e9b550e4d691cdbf19c
  <- 2157ab7d530c8a65996682fc63b1016bef517d07
```

其他拓扑核对：

- `merge-base(2157ab7, 5c3c623)` 精确等于固定基线；
- 固定基线是来源提交祖先，`merge-base --is-ancestor` 退出 `0`；
- `2157ab7..5c3c623` 只有 1 个提交；
- 来源 worktree 在集成前为干净状态；
- 固定基线到来源提交的 stable patch-id 为 `da5fdc1c9dee8faeca7291a98081f18be4b3a1ff`；
- 协调分支通过 fast-forward 直接指向来源提交，没有生成重写 SHA 或内容不同的 cherry-pick；
- `git diff --check 2157ab7..5c3c623` 通过。

M0-01 只新增以下 10 个文件，全部位于冻结给 M0-01 的测试、测试资源和 handoff 所有权范围：

```text
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/CurrentAuthenticationBaselineTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/CurrentDocumentServiceFailureBaselineTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/CurrentFileServiceBaselineTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/CurrentJdbcMigrationBaselineTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/CurrentStructuredApiBaselineTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/M001CapabilityMatrixTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/PendingImplementationContractTest.java
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/RepositorySourceEvidence.java
ai-service/src/test/resources/review-m0/baseline/m0-01-capability-matrix.json
docs/review-m0/handoffs/M0-01.md
```

相对固定基线，以下禁改热点逐文件 diff 为 0：根/`ai-service` POM、`application.yml`、`application-local.yml`、`AgentExecutionService.java`、`AgentToolRegistry.java`、`web/src/router/index.js`、`docs/PROGRESS.md`、`scripts/review-m0/**`。

## 3. 能力矩阵与 Disabled 复核

`m0-01-capability-matrix.json` 与 `M0-01.md` 一致：

- 共 13 个能力条目，5 个只表示当前行为已验证，8 个表示当前缺口；
- 所有外部依赖均声明未使用，`productionCodeChanged=false`；
- 8 个条目明确带 `PENDING_IMPLEMENTATION` 下游目标；
- `AI_REAL_MYSQL_STARTUP` 保持 `CURRENT_GAP`，目标状态不伪装成实现任务结果，由 `M0-02_INTEGRATION` 承担真实 MySQL 8 验证；
- `AI_STRUCTURED_HTTP_ERRORS` 的 `CURRENT_VERIFIED` 只指现有 Conversation API 的两字段载体和 HTTP 映射；Review M0 四字段载体仍为 M0-06 的 `PENDING_IMPLEMENTATION`。

`PendingImplementationContractTest` 的 15 个 Disabled 方法均为空测试体配合具体 `@Disabled` 原因，不会被计为通过能力：

| 分类 | 数量 | 状态/所有者 |
| --- | ---: | --- |
| 文件 owner、稳定快照/摘要、流式读取、ZIP 单叶子与安全上限 | 7 | `PENDING_IMPLEMENTATION / M0-04` |
| PDF/DOCX locator、XLSX、真实 OCR、typed failure | 5 | `PENDING_IMPLEMENTATION / M0-03` |
| Review 独立版本化 JDBC 迁移 | 1 | `PENDING_IMPLEMENTATION / M0-02` |
| Review 四字段错误载体 | 1 | `PENDING_IMPLEMENTATION / M0-06` |
| 真实 MySQL 8 启动与迁移 | 1 | `EXTERNAL_VERIFICATION_PENDING / M0-02 integration` |

另有 M0-PB 的 2 个 Disabled，分别保留真实 JPG/PNG OCR 环境边界。因此精确集合的 17 个 skipped 全部有明确原因，没有空断言假通过。

## 4. 实际命令与结果

所有命令均在 M0-01 原样集成后的干净提交 `5c3c623be142ef26403f9e9b550e4d691cdbf19c` 上，从协调 worktree 根执行。

### 4.1 解析器运行时依赖

```powershell
.\mvnw.cmd -q -pl document-service -am dependency:build-classpath `
  '-Dmdep.outputFile=target/m0-pb-runtime-classpath.txt' `
  '-Dmdep.includeScope=runtime'
```

退出 `0`。该步骤只生成测试 harness 所需运行时 classpath。

### 4.2 M0-01、M0-PB 与精确旧基线

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service `
  '-Dtest=com.javaee.aiservice.review.m0.baseline.*Test,AgentExecutionServiceTest,AgentControllerTest,ConversationControllerTest,JdbcConversationStoreTest' test
```

- 99 discovered / 82 passed / 0 failures / 0 errors / 17 skipped；
- `BUILD SUCCESS`，退出 `0`；
- 17 skipped 为上节列明的 15 个 M0-01 边界和 2 个真实 OCR 边界。

### 4.3 文档服务现有单测与文件服务编译

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl document-service '-Dtest=DocumentServiceImplTest' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -DskipTests compile
```

- `DocumentServiceImplTest`：4 discovered / 4 passed / 0 failures / 0 errors / 0 skipped，退出 `0`；
- `file-service compile`：`BUILD SUCCESS`，退出 `0`；没有把编译成功或既有 mock 单测写成真实文件/文档服务验证。

### 4.4 ai-service 隔离应用上下文

按 M0-01 handoff 使用非秘密合成占位环境变量、四个 indexed 模型禁用属性和 RabbitMQ listener 禁用属性运行：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service `
  '-Dtest=AllModelsTest' `
  '-Dai.models.openai[qwen3.7-max-2026-05-20].enabled=false' `
  '-Dai.models.openai[glm-5].enabled=false' `
  '-Dai.models.openai[kimi-k2.5].enabled=false' `
  '-Dai.models.openai[MiniMax-M2.5].enabled=false' `
  '-Dspring.rabbitmq.listener.simple.auto-startup=false' test
```

- 1 discovered / 1 passed / 0 failures / 0 errors / 0 skipped；
- `BUILD SUCCESS`，退出 `0`；
- 四个远端模型均禁用，真实模型成功数为 0/4；
- 日志记录本地 Redis/HNSW 与监控后台连接失败，未使上下文测试失败，也不构成 Redis 验证；
- MinIO client 只以非秘密占位配置构造，未执行真实对象存储链路。

### 4.5 默认资产门禁

```powershell
scripts\review-m0\run-m0-acceptance.cmd
```

- 退出 `0`；
- 26 discovered / 26 passed / 0 failures / 0 errors / 0 skipped；
- 敏感信息门扫描 901 个 Git 可见文本文件，无命中；
- 报告为 `ASSET_VERIFIED / NOT_READY`，`finalAcceptance=false`；
- 15 个 P0 生产计数全部为 `null`，没有把资产测试写成生产通过。

### 4.6 Integrated 门禁

```powershell
scripts\review-m0\run-m0-acceptance.cmd -Integrated
```

- 按设计退出 `2`；
- 资产自检 26 discovered / 26 passed / 0 failures / 0 errors / 0 skipped；
- 敏感信息门扫描 901 个 Git 可见文本文件，无命中；
- 报告为 `PENDING_INTEGRATION / NOT_READY`，`finalAcceptance=false`；
- 15 个 P0 生产计数仍全部为 `null`；
- `readinessMissing` 精确为：
  - `REVIEW_M0_PRODUCTION_ADAPTER_NOT_DECLARED`；
  - `REVIEW_M0_EXACT_INTEGRATED_TEST_SELECTION_NOT_DECLARED`；
  - `REVIEW_M0_STATIC_SCAN_ROOTS_NOT_DECLARED`。

本次所有测试命令合计 156 次测试调用：139 passed、0 failures、0 errors、17 skipped。默认与 Integrated 门禁各自重复执行同一组 26 项资产测试，已分别计入该实际调用总数；编译和 classpath 检查不计为测试。

## 5. 冻结给 M0-02 的现有事实

以下是 M0-02 可以直接依赖的现有事实，不是 Review 生产能力声明：

1. **稳定 owner 字符串入口**：认证成功后，实际 JWT filter 把 JWT `userId` 作为 `Long` principal 写入 SecurityContext；`RequestUserContext#getRequiredUserId()` 对 principal 调用 `toString()`，例如 `42L -> "42"`。缺失或无效认证必须失败。请求体 `userId`、raw `X-User-Id` 或 bucket 推导不能代替该入口。
2. **迁移启动模式**：当前 Memory 使用实现 `InitializingBean` 的 `MemorySchemaInitializer`；持久化关闭时不访问 DataSource，开启时只有显式打开 auto-migration 才按版本用 `ResourceDatabasePopulator` 执行 SQL，随后无条件 `validate()`，缺 schema 时 fail-fast。M0-02 可复用这种启动范式，不可复用 Memory 表或 V001/V002 作为 Review schema。
3. **H2 MySQL-mode 形态**：当前确定性测试使用 `jdbc:h2:mem:<name>;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1`，并执行实际版本 SQL、幂等二次启动和缺 schema 失败测试。它只证明 H2/MySQL-mode 兼容形态，不证明真实 MySQL 8。
4. **Review 独立迁移/开关**：当前 `ai-service/src/main/resources/db/review-m0/` 不存在，也没有 Review 专属 migration 开关。M0-02 必须在 Review 自有资源/执行器中增加独立版本化迁移和独立开关，不修改公共 YAML，不复用 `ai.memory.schema.auto-migration.enabled`，不把 DDL 混入 `db/memory/V001/V002`。

## 6. 文件服务缺口保持未实现

以下 M0-01 事实继续是 M0-04 的起点，未在本次集成中修复：

- `/api/files/**` 当前 `permitAll`，按 `fileId` 的元数据/下载链路没有稳定文件级 owner 谓词；
- `X-User-Id` 回退和用户 bucket 选择不是 `getOwnedFile(userId,fileId)`；
- 没有原子绑定原文件名、字节、大小、storage version 和 SHA-256 的 owned snapshot；
- 当前下载物化整个 `byte[]`，没有 bounded streaming/range 契约；
- 没有 digest-bound ZIP 单叶子读取或冻结的路径、嵌套、加密、损坏、类型和展开安全边界。

因此 M0-02 不得先发明文件适配实现，M0-04 也不能把当前 `download(fileId)` 或 bucket/header 行为声明为 owner-aware 能力。

## 7. 外部依赖与未验证项

本次没有成功连接或验证真实 MySQL、Redis、RabbitMQ、MinIO、文件服务、文档服务、Tesseract/traineddata、远程模型或浏览器；没有运行完整服务或双用户 JWT 端到端。

仍未验证：

- Review M0 生产包、独立表/迁移、Store/Job SPI、Controller、文件适配、ZIP 读取、候选提取、确定性引擎、报告和生产装配；
- Integrated 成功路径、15 个 P0 生产行为和 `AUTO_VERIFIED`；
- 真实 MySQL 8 启动、迁移、约束和重启；
- 真实文件/文档服务网络链路、MinIO 字节/摘要/所有者一致性；
- 真实 PDF/DOCX/XLSX 定位、图片 OCR、加密/损坏/大文件和危险 ZIP；
- 真实模型、浏览器、跨用户与人工验收；
- `P-01` 至 `P-13` 的产品选择。

没有发现需要产品选择的新冲突；本次没有决定任何待确认项。

## 8. Wave 1 进入条件

M0-02 只能从包含 M0-01 原样提交的本集成分支后续完整 handoff commit 开始，并遵守 `03-TASKS_OWNERSHIP_AND_INTEGRATION.md` 的独占范围：只实现领域、Store、Job、幂等和 `db/review-m0/**`，不实现文件、解析、候选、审查或 API，不修改公共 POM/YAML、Agent 热点、验收资产、Web 或 `docs/PROGRESS.md`。

M0-02 阶段完成前，Review M0 状态继续为 `PENDING_INTEGRATION`；不得把 H2、资产门或当前行为基线写成 Review 生产能力。
