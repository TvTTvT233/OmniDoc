# Memory V1 M01-BE：后端行为基线交接

## 1. 状态

```text
HANDOFF_READY / PRODUCTION_CODE_UNCHANGED
```

- 分支：`test/memory-m01-backend`
- 基线：`37f81f7495f16c304b2b7b04dab1ab7783b67cae`
- 交付提交：本文件所在提交；精确 SHA 由协调任务在挑选提交时记录
- 所有权：`ai-service` Conversation、Context、Agent 当前行为测试与本交接文档

本任务只建立 M01 确定性后端基线，没有修改生产 Java、配置、数据库结构，也没有修改 `ai-service/src/test/resources/memory/**`。

## 2. 已建立的当前行为基线

新增 16 个可执行、通过型行为刻画测试：

| 测试类 | 数量 | 证明的当前行为 |
| --- | ---: | --- |
| `ConversationManagerCharacterizationTest` | 5 | `conv:*` Hash、24 小时滑动 TTL、字符串消息、条数截断、长消息无 token 预算、读改写并发风险、删除边界 |
| `ContextManagerCharacterizationTest` | 4 | `ctx:*` 任意字段、无 TTL、整体删除重写、并发更新风险、单独清理边界 |
| `AgentConversationMemoryCharacterizationTest` | 3 | Direct Answer 不读取历史、Planner/Reflection/Final 各自拼 Prompt、旧 Context 跨 turn 回流 |
| `AgentControllerConversationBaselineTest` | 4 | 认证用户所有权、旧字符串历史、旧 End 永久删除语义、旧 Chat 到统一 Agent 的请求形状 |

并发测试使用两个线程和屏障，让两个操作确定性读取同一快照，再验证各自的整体写回均缺少另一方更新。该测试证明当前读改写算法的丢更新窗口，不声称完成真实 Redis 压力测试。

## 3. V1 目标缺口清单

`MemoryV1ExpectedContractTest` 新增 16 个逐项标注的 `@Disabled` 目标契约：

| 后续任务 | 跳过数 | 目标范围 |
| --- | ---: | --- |
| M02 | 4 | 结构化消息与 sequence、幂等追加、重启/Redis 过期恢复、所有者隔离 |
| M03 | 4 | Runtime 白名单与 TTL、跨会话隔离、级联清理、Service 内续接/审批所有权 |
| M04 | 4 | 统一 Context Builder、消息连续性、工具对原子裁剪、超预算稳定错误 |
| M05 | 4 | coverage/digest、事实与决定状态、Summary CAS、失败不推进 coverage |

这些测试当前状态是 `PENDING_IMPLEMENTATION`，不是通过。对应任务实现时必须用真实断言替换占位并移除 `@Disabled`；M07 最终验收要求 Memory 目标 skipped 为 0。

## 4. 实际验证

### 新增专项

```powershell
.\mvnw.cmd -pl ai-service -am "-Dtest=ConversationManagerCharacterizationTest,ContextManagerCharacterizationTest,AgentConversationMemoryCharacterizationTest,AgentControllerConversationBaselineTest,MemoryV1ExpectedContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：32 个测试被发现，16 个当前行为测试通过，16 个目标契约按计划跳过；Failures 0、Errors 0，`BUILD SUCCESS`。

首次执行曾有 1 个测试夹具失败：Direct Answer 所用 `InternalService` Mock 未放行工具权限，链路进入权限错误后的 Final Synthesis。补齐测试 Mock 后按同一命令重跑通过；未修改生产代码。

### 新增专项加既有 Agent/Controller 回归

```powershell
.\mvnw.cmd -pl ai-service -am "-Dtest=ConversationManagerCharacterizationTest,ContextManagerCharacterizationTest,AgentConversationMemoryCharacterizationTest,AgentControllerConversationBaselineTest,MemoryV1ExpectedContractTest,AgentExecutionServiceTest,AgentControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：64 个测试被发现，48 个通过，16 个目标契约按计划跳过；Failures 0、Errors 0，`BUILD SUCCESS`。其中原有 `AgentExecutionServiceTest + AgentControllerTest` 仍为 32/32。

## 5. 集成说明

- 本提交不包含共享热点生产文件，可以在 M01-FE/M01-AC 任一顺序集成；
- `MemoryV1ExpectedContractTest` 的 16 个 skip 必须在 M01 报告中计为待实现，不得用于宣称 `BASELINE_VERIFIED` 以后的目标状态；
- M02-M05 启用目标测试时，优先保留测试方法名称和任务归属，替换内部占位实现；
- 行为刻画测试描述的是旧实现，后续生产语义改变时应将对应测试迁移为兼容测试或由目标契约替代，不能为了维持旧缺陷而扭曲实现。

## 6. 尚未验证与风险

- 未连接真实 Redis、MySQL、模型、MCP、Qdrant 或完整服务；
- 未运行完整 `ai-service`、RAG/MCP/Qdrant 专项或浏览器端到端；这些属于后续集成和 M07 门禁；
- 单元并发夹具证明算法风险，不代表测得真实吞吐、锁竞争或生产丢失率；
- 当前测试输出仍能观察到生产代码在 INFO 日志记录完整 task；日志脱敏属于后续生产实现，不在 M01-BE 修改范围；
- 16 个目标契约仍全部未实现，最终验收前必须全部启用并通过。
