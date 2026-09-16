# Memory V1 验收入口

本目录提供 Memory V1 的资产自检、阶段门禁和 M07 最终自动门禁。门禁不修改或替代生产实现，也不会把本地确定性替身写成真实外部服务验证。

## 资产自检

在仓库根目录执行：

```powershell
.\scripts\memory\run-m07-acceptance.cmd
```

成功状态只能是：

```text
ASSET_VERIFIED / PENDING_INTEGRATION
```

脚本生成：

```text
ai-service/target/memory-v1-acceptance/memory-v1-report.json
```

报告只包含测试计数、环境边界和未评估门禁，不复制消息、摘要、文档、Prompt、选区或工具结果正文。资产自检不连接 MySQL、Redis、真实模型、远程 Embedding 或 Qdrant。

## 集成门禁

```powershell
.\scripts\memory\run-m07-acceptance.cmd -Integrated
```

最终入口要求干净工作区，并执行以下硬边界：

- readiness 扫描 M02-M05、M06-A/B、M06-C 和 `MemoryV1IntegratedGateTest`；
- Maven 只使用逐名列出的测试类，不使用 `*Memory*Test`；
- 不设置 `surefire.failIfNoSpecifiedTests=false`；每个精确类必须有源码、Surefire suite XML 和合法 testcase 计数；
- 每次 Maven 运行前删除精确 `ai-service/target/surefire-reports/TEST-*.xml`，防止旧报告混入；
- M02/M03/M04/M05 的 16 个冻结目标方法必须逐名执行且 skipped 为 0；
- M06 前端三组精确门禁（契约 25、翻转基线 6、独立 UI 验收 11）和 M06-B 后端命名测试必须通过；
- 前端异步任务测试与生产构建必须通过；
- `MemoryV1IntegratedGateTest` 必须生成删除残留、sequence、coverage、隔离、CAS 与 token 预算证据，所有删除残留必须为 0；
- 运行后工作区仍须保持干净。

当前主协调基线已经集成 M06-C。2026-08-11 在 HEAD `40ef1cf` 上运行完整入口，资产 16 + 集成 171 = 187，Failures 0、Errors 0、Skipped 0，状态为 `AUTO_VERIFIED / MANUAL_PENDING`。报告仍明确保留真实浏览器/JWT、MySQL、Redis、模型、Qdrant 和远程 Embedding 为未验证边界；`AUTO_VERIFIED` 只表示本地确定性门禁通过。

## 分阶段门禁

M07-A/M07-B 增加可独立运行的阶段门禁。默认模式只验证门禁脚本和报告契约：

```powershell
.\scripts\memory\run-m02-gate.cmd
.\scripts\memory\run-m03-runtime-gate.cmd
.\scripts\memory\run-frontend-contract-gate.cmd
```

默认成功只表示 `ASSET_VERIFIED`。阶段门禁报告始终满足：

- `reportType=STAGE_GATE`；
- `finalAcceptance=false`；
- `manualStatus=NOT_READY`；
- 状态不能为 `AUTO_VERIFIED`。

### M02 持久化门禁

M02 合入共同分支后执行：

```powershell
.\scripts\memory\run-m02-gate.cmd -Integrated
```

门禁检查 Conversation model/store、`MemorySchemaInitializer`、Spring JDBC 与 MySQL Driver 是否存在，然后运行固定选择：

```text
MemoryV1ExpectedContractTest
M02 Conversation persistence/store/schema 专项通配
AgentExecutionServiceTest
AgentControllerTest
```

四个冻结的 `m02*` 目标 testcase 必须逐名存在且 skipped 为 0。实现或测试缺失时输出 `PENDING_INTEGRATION` 并非零退出；测试失败输出 `FAILED`。阶段通过只输出 `STAGE_VERIFIED`，不替代真实 MySQL 重启、后续 M03-M06 或最终 M07。

报告：

```text
ai-service/target/memory-v1-acceptance/memory-m02-stage-report.json
```

### M03-A Runtime Core 门禁

M03-A 合入共同分支后执行：

```powershell
.\scripts\memory\run-m03-runtime-gate.cmd -Integrated
```

门禁检查受控 `RuntimeState` model/service、独立 Store/Repository 或 Service 直接封装 `RedisTemplate` 的 Redis 持久化、字段白名单、分层 TTL、按字段或会话清理接口和 RuntimeState 专项测试，然后运行固定选择：

```text
MemoryV1ExpectedContractTest
RuntimeState 专项测试
ContextManager compatibility tests（如存在）
```

以下两个 M03-A 目标 testcase 必须逐名存在且 skipped 为 0：

- `m03RuntimeStateUsesWhitelistAndBoundedTtl`；
- `m03RuntimeStateDoesNotLeakAcrossConversations`。

以下两个目标属于 M03-B，在 M03-A 阶段必须继续保留测试清单，但允许保持 Disabled：

- `m03ConversationDeletionCascadesRuntimeDerivatives`；
- `m03ContinuationAndApprovalCancellationEnforceOwnershipInsideService`。

即使 M03-A 阶段通过，报告的 `readinessMissing` 和 `unverified` 仍明确记录 `M03B_DELETION_CASCADE_PENDING` 与 `M03B_OWNER_APPROVAL_PENDING`。状态最多为 `STAGE_VERIFIED`，`finalAcceptance=false`，不得描述为完整 M03 或最终 M07 通过。

报告：

```text
ai-service/target/memory-v1-acceptance/memory-m03a-runtime-stage-report.json
```

### M06-A 前端契约门禁

M06-A 合入共同分支后执行：

```powershell
.\scripts\memory\run-frontend-contract-gate.cmd -Integrated
```

默认要求：

- `web/src/api/conversation.js` 使用完整 `/api/ai/conversations`，或在项目 `baseURL=/api` 下使用等价的 `/ai/conversations` 资源路径；
- `web/src/utils`、`web/src/composables` 或 `web/src/stores` 中存在文件名含 `conversation` 且实际维护 `conversationId` 的状态模块；
- `web/package.json` 提供 `test:memory-contract`；
- 前端目标测试至少 1 项且 skipped 为 0；
- `AgentExecutionServiceTest + AgentControllerTest` 回归通过。

如 M06-A 使用合理的不同状态模块路径或脚本名，可显式传入 `-ConversationStatePath` 或 `-FrontendTestScript`；API 模块路径可用 `-ConversationApiPath` 指定。该阶段门禁只验证 M06-A 前端契约骨架，不要求后端结构化 API 已实现，也不代表 M06-B/C 或最终 M07 完成；最终状态以完整 `run-m07-acceptance.cmd -Integrated` 的报告为准。

报告：

```text
ai-service/target/memory-v1-acceptance/memory-m06a-frontend-stage-report.json
```

所有阶段报告都只包含 Git/环境元数据、测试计数、目标标识、缺失标识和未验证原因，不复制会话、摘要、文档、Prompt、选区、工具结果或凭据正文。
