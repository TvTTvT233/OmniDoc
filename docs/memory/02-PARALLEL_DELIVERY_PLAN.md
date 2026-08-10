# OmniDoc Memory V1：并行开发与集成交付计划

## 1. 状态

```text
EXECUTION_CONTRACT_FROZEN / M01_BASELINE_VERIFIED / M02_PENDING
```

本计划约束多个 Codex 同时开发时的依赖、文件所有权、集成顺序和状态声明。公共 API、存储、幂等、Runtime、token 和兼容语义以 `02-API_AND_STORAGE_CONTRACT.md` 为准。并行任务通过不等于集成通过，自动验收通过也不等于用户人工验收通过。

## 2. 交付状态机

```text
CONCEPT_FROZEN
  -> BASELINE_VERIFIED
  -> IMPLEMENTED
  -> INTEGRATED
  -> AUTO_VERIFIED
  -> MANUAL_PENDING
  -> HUMAN_ACCEPTED
```

- `BASELINE_VERIFIED`：当前缺口和已有回归已由确定性测试记录；
- `IMPLEMENTED`：专项任务在隔离分支完成并自测；
- `INTEGRATED`：专项提交按冻结顺序进入主协调分支；
- `AUTO_VERIFIED`：`03-AUTO_ACCEPTANCE.md` 的所有 P0/P1 门禁通过；
- `MANUAL_PENDING`：自动交付完成，等待用户执行 `04-HUMAN_ACCEPTANCE.md`；
- `HUMAN_ACCEPTED`：仅在用户确认人工用例后由主协调任务记录。

禁止跨级宣称状态；静态代码存在、单个专项测试通过或子任务自报完成都不能替代集成验收。

## 3. 并行原则

- 主协调任务拥有冻结文档、公共契约、集成顺序、冲突处理和最终报告；
- 每个开发任务使用独立 Git 分支和工作树，不在主工作树直接并发写入；
- 同一波次优先分配互不重叠的文件；共享热点文件只允许一个任务修改，或由主协调任务在集成阶段修改；
- 每一波必须从上一波已经集成并通过门禁的共同提交创建；
- 子任务不得顺手实现 Workspace/User Memory、Embedding 召回或奖学金审查专用记忆；
- 子任务必须提交专项测试、实际测试结果、未验证事项和交接提交；
- 主协调任务只挑选明确交付提交，不合并未提交工作树状态。

## 4. 开发波次

### Wave 0：M01 基线与验收夹具

可以并行：

| 任务 | 所有权 | 交付 |
| --- | --- | --- |
| M01-BE | `ai-service` 现有 Conversation/Context/Agent 测试 | 后端行为刻画、目标契约测试、精确回归命令 |
| M01-FE | `web` 会话状态与 API 调用测试 | 刷新、新对话、临时 ID、双入口差异的基线 |
| M01-AC | `scripts/memory/**`、Memory 验收 fixture/报告 | 统一验收入口、PENDING/通过语义、报告 Schema |

Wave 0 不得修改生产行为。无法在当前实现通过的目标用例可以明确标记为 `PENDING_IMPLEMENTATION`，但最终 M07 不允许保留目标跳过项。

集成门禁：现有回归无新增失败；报告能够区分“当前行为已证明”和“V1 目标尚未实现”。

### Wave 1：M02 持久化、M06 API/前端骨架、M07 验收骨架

在 Wave 0 集成后并行：

| 任务 | 主要所有权 | 禁止修改 |
| --- | --- | --- |
| M02 | `conversation/model/**`、`conversation/store/**`、Memory 数据库结构、`ConversationManager` 兼容层 | `AgentExecutionService`、`ContextManager`、`web/**` |
| M06-A | 新增 Conversation DTO/API 契约测试与 `web` 会话 API/状态模块 | 消息存储、摘要、Agent Prompt |
| M07-A | `scripts/memory/**`、合成 fixture、自动报告 | 生产 Java/Vue 实现 |

共享热点 `ai-service/pom.xml`、配置文件和 `AgentController` 由主协调任务按 M02 -> M06-A -> M07-A 顺序集成。

Wave 1 门禁：MySQL 成为事实主库；重启后可读；sequence 单调且唯一；跨用户访问为零；原有 API 兼容测试通过。

### Wave 2：M03 Runtime State 与 M06 完整会话体验

在 M02 集成后并行：

| 任务 | 主要所有权 | 禁止修改 |
| --- | --- | --- |
| M03 | `conversation/runtime/**`、`ContextManager` 兼容适配、Runtime State 测试 | 摘要、前端、Agent Prompt |
| M06-B | Conversation 列表/消息/归档/删除 Controller 与 `web` UI | `AgentExecutionService`、Summary 实现 |

Wave 2 门禁：Runtime State 有字段白名单和 TTL；新对话不继承旧选区/文档/工具状态；刷新恢复、归档、删除和级联缓存清理通过。

### Wave 3：M04 统一上下文组装

M04 独占 Agent 模型调用主链，避免并行修改热点文件：

- 新增 `memory/context/**`；
- 统一 Planner、Direct Answer、Reflection、完成度判断和 Final Synthesis；
- 修改 `AgentExecutionService` 及必要的 Reflection 接口；
- 不实现摘要生成，只消费可为空的 Summary Provider。

Wave 3 门禁：近期多轮指代通过；所有模型调用路径有相同上下文边界；token 预算、工具调用配对、无重复/无缺口测试通过。

### Wave 4：M05 Structured Summary 与 M06 收尾

在 M04 集成后可以并行：

| 任务 | 主要所有权 | 禁止修改 |
| --- | --- | --- |
| M05 | `memory/summary/**`、Summary 持久化和模型调用 | 前端、文档 RAG/Qdrant Collection |
| M06-C | 摘要上线后的历史展示、错误恢复和 UI 收尾 | Summary Prompt、Agent 主链 |

主协调任务负责把 Summary Provider 接入 M04，不允许两个任务同时修改 `AgentExecutionService`。

Wave 4 门禁：长对话事实召回；摘要来源/状态校验；CAS；失败不推进 coverage；摘要模型不可用时主会话保持可用。

### Wave 5：M07 独立自动验收与交付

M07 从集成提交独立复核，不修生产缺陷。发现缺陷时退回对应 M02-M06 任务，修复重新集成后从头运行 M07。

完成条件：

- `03-AUTO_ACCEPTANCE.md` 全部硬门禁通过；
- 生成机器可读报告和脱敏的人类摘要；
- `docs/PROGRESS.md` 记录真实测试和剩余风险；
- 状态为 `AUTO_VERIFIED / MANUAL_PENDING`；
- 向用户交付 `04-HUMAN_ACCEPTANCE.md`，不代替用户勾选。

## 5. 公共接口冻结

为降低并行冲突，以下接口语义冻结：

- 现有 `POST /api/ai/agent/chat/start` 继续返回 `conversationId` 字符串，所有者始终取认证上下文，忽略客户端伪造的 userId；
- 现有 `POST /api/ai/agent/chat` 和异步 Agent 入口继续接受 `conversationId`；
- 现有 `GET /api/ai/agent/chat/{conversationId}/history` 在兼容期保留字符串列表语义；
- 新增结构化接口使用 `/api/ai/conversations` 资源命名，提供列表、详情、分页消息、归档、恢复和永久删除；
- 新接口统一从认证上下文确认所有者，不接受请求体中的 userId 作为授权依据；
- 永久删除使用 `DELETE`；归档/恢复不得复用删除接口；
- 不存在、已删除和无权访问统一对外返回 `CONVERSATION_NOT_FOUND`；无效游标、归档写入和并发冲突使用独立稳定错误；
- 分页以稳定 cursor/sequence 为边界，不以数组下标作为公共契约。

新结构化接口的 DTO 字段由 M02/M06 契约测试先行冻结；不得直接把数据库实体、内部 System Prompt 或完整工具结果暴露给前端。

## 6. 集成顺序

```text
M00 docs
-> M01-BE / M01-FE / M01-AC
-> M02
-> M06-A
-> M07-A
-> M03
-> M06-B
-> M04
-> M05
-> M06-C
-> M07 final
```

每次集成后至少运行受影响专项测试和 `AgentExecutionServiceTest + AgentControllerTest`。进入下一波前必须保存一个可定位的共同提交。

## 7. 明确不在本轮并行计划内

- Workspace Memory 和 User Memory；
- 自动记忆候选、确认、修改、遗忘 UI；
- Embedding、Qdrant 记忆索引和跨会话语义召回；
- 奖学金审查专用 Agent 接入；
- 生产级备份、灾备、全库加密、法务合规认证；
- 对现有文档、RAG、MCP、Qdrant 链路的无关重构。
