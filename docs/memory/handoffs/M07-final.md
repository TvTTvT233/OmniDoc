# Memory V1 M07 最终自动验收门禁交接

## 1. 状态与边界

```text
DETERMINISTIC_GATES_VERIFIED / PENDING_INTEGRATION / MANUAL_PENDING / PENDING_EXTERNAL
```

- 分支：`test/memory-m07-final`；
- 起始 HEAD：`6ebdb2f523c30681097745d914bd7f8bff9f841d`；
- 生产实现、POM、配置和前端 UI：未修改；
- 当前基线未集成 M06-C，readiness 精确缺少 `M06C_GLOBAL_ASSISTANT_UI` 与 `M06C_EDITOR_UI`；
- 最终结论不是 `AUTO_VERIFIED`，也不是 `HUMAN_ACCEPTED`。

本交付只拥有 `scripts/memory/**`、Memory acceptance 测试、必要报告 Schema fixture 和本交接文档。由于任务文件所有权明确排除 `docs/PROGRESS.md`，本任务未修改该文件，真实状态全部记录在本交接中。

## 2. 门禁修正

### Readiness

- 后端结构化 Conversation API 从原错误目录 `controller/**` 改为扫描真实路径 `conversation/api/ConversationController.java`；
- M02、M03、M04、M05、M06-A、M06-B、M06-C 和 M07 分别生成稳定缺失标识；
- readiness 可写出独立 JSON，当前只报告两个 M06-C UI 接管缺口，没有把 M06-A/B 误报为缺失或完整 M06。

### 精确测试聚合

- `run-m07-acceptance.ps1` 不再使用 `*Memory*Test`；
- 最终 Java 聚合逐名列出 18 个测试类，并逐名校验 M02/M03/M04/M05 共 16 个冻结目标方法；
- M06 前端只运行两个明确文件：`conversation.memory-contract.test.js` 与 `useConversationSession.memory-contract.test.js`；
- 不再设置 `surefire.failIfNoSpecifiedTests=false`。先检查每个精确源码类，再要求每个类都生成同名 Surefire suite XML，缺类、缺 suite、额外 suite、缺 testcase 或 skipped 都不能通过；
- Maven 依赖先以跳过测试的独立步骤准备，随后只在 `ai-service` 执行精确选择，避免上游 `common` 无匹配测试，同时不放宽 `ai-service` 缺类语义。

### XML、工作区和清理

- 每次 Maven 选择前只清理精确目录 `ai-service/target/surefire-reports/TEST-*.xml`；
- 使用 XML DOM 重新加载报告，并核对 suite 声明计数与实际 testcase/failure/error/skipped 节点；
- `-Integrated` 运行前、运行后都要求 Git 工作区干净；
- 新增真实运行的 `MemoryV1IntegratedGateTest`，跨 H2/MySQL-mode Conversation/Message/Summary、Summary CAS、MemoryContextBuilder、owner 隔离和永久删除生命周期生成机器证据；
- 删除残留逐项校验 Conversation、Message、Summary、Redis fixture key 和 Runtime State fixture key 均为 0。

## 3. 实际验证与真实计数

### 资产门禁

```powershell
.\scripts\memory\run-m07-acceptance.cmd
```

- 16/16 通过；Failures 0、Errors 0、Skipped 0；
- Surefire XML 精确包含 3 个资产类；
- 静态敏感内容检查扫描 854 个 Git 可见文本文件，无命中；
- 报告状态 `ASSET_VERIFIED`，没有执行或计入生产门禁。

### 新增跨层测试

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service "-Dtest=MemoryV1IntegratedGateTest" test
```

- 1/1 通过；Failures 0、Errors 0、Skipped 0；
- 证据：message loss 0、duplicate sequence 0、coverage gap 0、coverage duplicate 0、stale overwrite 0、unsupported confirmed items 0、cross-user success 0、token over-budget 0、model call paths 7/7；
- 删除残留：Conversation 0、Message 0、Summary 0、Redis fixture key 0、Runtime State fixture key 0；
- token 证据：estimated 172、input budget 3840。

### 精确 Java 聚合

- 18 个逐名测试类，129/129 通过；Failures 0、Errors 0、Skipped 0；
- 其中 M02-M05 冻结目标 16/16 全部执行，无 Disabled/Skipped；
- 包含 M02–M06-B 专项、M07 Integrated、`AgentExecutionServiceTest` 与 `AgentControllerTest` 回归。

### 精确前端契约

- 2 个逐名文件、14/14 通过；Failures 0、Skipped 0；
- 该结果只证明 M06-A 契约骨架，不证明 M06-C UI 接管或真实浏览器行为。

### 最终干净工作区入口

在提交后的干净工作区执行：

```powershell
.\scripts\memory\run-m07-acceptance.cmd -Integrated
```

- 可运行确定性测试合计：资产 16 + 集成 143 = 159；Failures 0、Errors 0、Skipped 0；
- `status=PENDING_INTEGRATION`、`finalAcceptance=false`、`manualStatus=MANUAL_PENDING`；
- 实际非零退出，原因仅为 M06-C 两个依赖未集成；
- 报告路径：`ai-service/target/memory-v1-acceptance/memory-v1-report.json`。

## 4. 未验证项

机器报告明确保留：

```text
PENDING_INTEGRATION:M06C_GLOBAL_ASSISTANT_UI
PENDING_INTEGRATION:M06C_EDITOR_UI
PENDING_EXTERNAL:REAL_BROWSER_JWT
PENDING_EXTERNAL:REAL_MYSQL
PENDING_EXTERNAL:REAL_REDIS
PENDING_EXTERNAL:REAL_MODEL
PENDING_EXTERNAL:REAL_QDRANT
PENDING_EXTERNAL:REMOTE_EMBEDDING
```

真实浏览器/JWT、多用户 UI、真实 MySQL 重启与锁行为、真实 Redis TTL/故障、真实摘要/回答模型、真实 Qdrant、远程 Embedding 均未运行。当前删除残留 0 来自 H2/MySQL-mode 与受控 Runtime/Redis fixture，不是外部基础设施清理证据。

## 5. 风险与下一步

- M06-C 完成前，现有 `GlobalAiAssistant.vue` 仍可能生成 `temp_*`，`EditorView.vue` 仍可能回退 `default_conv`；这是最终状态保持 `PENDING_INTEGRATION` 的直接原因；
- 前端依赖安装仍报告 12 个既有审计问题（3 moderate、7 high、2 critical），本任务未执行破坏性升级；
- 本地确定性测试不能替代真实 MySQL/Redis 并发、网络故障或真实模型质量；
- M06-C 合入后应重新运行同一入口。即使确定性门禁全部通过，只要外部项仍未验证，状态也只能进入 `PENDING_EXTERNAL / MANUAL_PENDING`，不得写成 `AUTO_VERIFIED`。
