# Memory V1 验收入口

本目录在 M01 阶段只验证合成 fixture、Summary JSON Schema、报告 JSON Schema、资产契约测试和敏感信息边界，不修改或替代 Memory V1 生产实现。

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

M02-M06 生产源组或 `MemoryV1IntegratedGateTest` 缺失时，入口输出 `PENDING_INTEGRATION` 并以非零状态结束，不会把缺失实现计作通过。脚本请求 `exit 2`；当前 Windows PowerShell 调用宿主实际呈现为进程退出码 `1`，调用方应以“是否为零”判断通过或挂起。

M01-AC 版本即使发现候选集成资产也没有权限输出 `AUTO_VERIFIED`。最终状态必须由 M07 在全部 P0/P1 门禁、回归和清理检查完成后实现并复核。

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

如 M06-A 使用合理的不同状态模块路径或脚本名，可显式传入 `-ConversationStatePath` 或 `-FrontendTestScript`；API 模块路径可用 `-ConversationApiPath` 指定。该门禁只验证 M06-A 前端契约骨架，不要求后端结构化 API 已实现，也不代表 M06-B/C 完成。完整 `run-m07-acceptance.cmd -Integrated` 仍应保持 `PENDING_INTEGRATION`。

报告：

```text
ai-service/target/memory-v1-acceptance/memory-m06a-frontend-stage-report.json
```

所有阶段报告都只包含 Git/环境元数据、测试计数、目标标识、缺失标识和未验证原因，不复制会话、摘要、文档、Prompt、选区、工具结果或凭据正文。
