# OmniDoc Memory V1：目标、作用域与冻结契约

## 1. 文档状态

- **状态**：`FROZEN`
- **冻结日期**：2026-08-11
- **实现状态**：尚未开始
- **适用范围**：OmniDoc 通用 Agent 与对话入口

本文档冻结 Memory V1 的产品目标、作用域、数据语义和行为不变量。冻结表示后续实现任务应共同遵守这些边界，不表示相关功能已经实现、启动或通过端到端验证。

## 2. 核心决策

OmniDoc 长期保留三级记忆架构：

1. `CONVERSATION`：一次对话中的目标、约束、稳定事实、决定、待办和近期消息；
2. `WORKSPACE`：同一工作对象在多个对话间共享的事实、决定、进度和资源关系；
3. `USER`：跨工作空间生效的稳定用户偏好。

**Memory V1 只实现 `CONVERSATION`。**

`WORKSPACE` 与 `USER` 只作为未来作用域保留，不在 V1 中写入、检索、注入模型或提供伪实现。`Runtime State` 不属于记忆体系，必须与三种记忆作用域分离。

```text
长期架构

USER
  └─ WORKSPACE
       └─ CONVERSATION

Memory V1 实际启用

CONVERSATION

独立于 Memory

RUNTIME_STATE
```

## 3. 为什么 V1 只做 Conversation Memory

- 当前最直接的缺口是消息已保存但没有稳定进入所有模型调用，多轮理解链路尚未闭环；
- 当前项目没有正式的 `workspaceId` 或审查案例实体，直接实现 Workspace Memory 会把技术作用域误当成业务作用域；
- User Memory 涉及长期偏好、隐私、自动晋升和用户管理，错误影响范围比会话记忆更大；
- Conversation Memory 可以先建立消息顺序、上下文预算、摘要、来源追踪、删除和并发控制等公共基础；
- V1 不需要向量召回，可以独立于 Qdrant 人工验收和真实 Embedding 可用性推进。

V1 的数据模型应使用可扩展的 `scopeType + scopeId` 概念，但 V1 运行时只允许 `scopeType=CONVERSATION`。未来新增作用域时不得改变 Conversation Memory 的既有语义。

## 4. 作用域与类型是两个维度

作用域回答“这条记忆在哪里生效”，类型回答“这条记忆表达什么”。二者不得建模为同一棵继承树。

### 4.1 作用域

| 作用域 | V1 状态 | 绑定对象 | 典型内容 |
| --- | --- | --- | --- |
| `CONVERSATION` | 实现 | `conversationId` | 当前对话的稳定状态与历史 |
| `WORKSPACE` | 预留 | 未来 `workspaceId/reviewCaseId` | 跨对话工作事实、决定和进度 |
| `USER` | 预留 | `userId` | 稳定语言、格式和交互偏好 |

`knowledgeBaseId` 可以在未来作为 Workspace 的关联字段，但不得永久等同于 Workspace。知识库是检索语料边界，Workspace 是任务与资源的业务边界。

### 4.2 Conversation Summary 类型

Memory V1 的结构化摘要只包含：

- `goal`：当前对话的一个主要目标；
- `constraints`：对当前对话持续有效的明确约束；
- `facts`：有来源、具有后续价值的会话事实；
- `decisions`：提出、确认、拒绝或替代的决定；
- `openQuestions`：尚未解决的问题；
- `todos`：待执行、执行中、完成或取消的事项；
- `resources`：当前对话持续关联的文档、知识库、网页或 Agent 产物引用。

当前选区、页面路径、审批令牌、当前 Trace、完整工具结果等属于 `Runtime State`，不得写入结构化摘要。

## 5. V1 目标

Memory V1 必须实现：

1. 使用结构化消息保存用户、助手和必要的工具消息；
2. 最近消息真正进入规划、直接回答、反思和最终合成等模型调用；
3. 使用“结构化摘要 + 未被摘要覆盖的近期原始消息 + 当前消息”组装上下文；
4. 按 token 预算而不是固定消息条数控制上下文；
5. 保证摘要与近期消息之间没有重复和缺口；
6. 将长期会话数据与短期运行状态分离；
7. 原始会话默认长期保留，用户删除时级联清理派生数据；
8. 对消息、摘要和来源提供稳定标识，支持审计与重新生成；
9. 保持现有 `conversationId` 和 Agent 入口的兼容演进；
10. 为后续 Workspace/User Memory 预留扩展边界，但不提前实现。

## 6. V1 明确不做

- 不实现 Workspace Memory；
- 不实现 User Memory；
- 不自动提取或晋升跨会话长期记忆；
- 不使用 Qdrant 召回旧对话或记忆；
- 不将聊天消息混入现有文档 RAG Collection；
- 不建立个人知识图谱、实体关系图或跨用户共享记忆；
- 不把模型回答、模型建议或模型自报置信度当作已确认事实；
- 不实现奖学金审查专用记忆链路；
- 不自动覆盖用户原始文件。

## 7. 概念数据模型

### 7.1 Conversation

```text
Conversation
├─ conversationId
├─ userId
├─ title
├─ status                 ACTIVE / ARCHIVED / DELETED
├─ createdAt
└─ updatedAt
```

### 7.2 ConversationMessage

```text
ConversationMessage
├─ messageId
├─ conversationId
├─ sequence               对话内单调递增
├─ role                   SYSTEM / USER / ASSISTANT / TOOL
├─ content
├─ messageType            TEXT / TOOL_CALL / TOOL_RESULT / EVENT
├─ traceId                可选
├─ createdAt
└─ metadata               受控扩展字段
```

原始消息是事实底稿。已提交消息默认不可原地改写；需要纠正时追加新消息或显式版本记录。

### 7.3 StructuredConversationSummary

```json
{
  "schemaVersion": "1.0",
  "conversationId": "conv_001",
  "summaryVersion": 3,
  "coverage": {
    "fromSeq": 1,
    "toSeq": 55,
    "sourceDigest": "..."
  },
  "goal": null,
  "constraints": [],
  "facts": [],
  "decisions": [],
  "openQuestions": [],
  "todos": [],
  "resources": [],
  "meta": {
    "previousSummaryVersion": 2,
    "generatedAt": "2026-08-11T00:00:00Z"
  }
}
```

`coverage` 是摘要与原始消息的分界线：

```text
Summary 覆盖 message 1..55
+ Raw message 56..70
+ Current message 71
```

摘要更新必须采用版本比较；并发生成的旧版本不得覆盖新版本。生成失败时不得推进 `coverage.toSeq`。

### 7.4 RuntimeState

```text
RuntimeState
├─ conversationId
├─ traceId
├─ currentDocumentId
├─ selectedText
├─ pageContext
├─ pendingApproval
├─ currentToolState
└─ expiresAt
```

Runtime State 使用短期存储与 TTL，不能因为存在于 Redis `ctx:*` 中就被视为长期记忆。

## 8. Summary Item 契约

除 `resources` 外，结构化摘要条目共享以下基础字段：

```text
SummaryItem
├─ id
├─ content
├─ status
├─ sourceRefs[]
└─ lastUpdatedSeq
```

状态集合冻结如下：

| 类型 | 允许状态 |
| --- | --- |
| `goal` | `active`, `completed`, `cancelled` |
| `constraint` | `active`, `superseded` |
| `fact` | `confirmed`, `uncertain`, `conflicting`, `superseded` |
| `decision` | `proposed`, `confirmed`, `rejected`, `superseded` |
| `openQuestion` | `open`, `resolved`, `dropped` |
| `todo` | `pending`, `in_progress`, `done`, `cancelled` |
| `resource` | `active`, `superseded`, `removed` |

### 8.1 SourceRef

```text
SourceRef
├─ type                   MESSAGE / DOCUMENT / TOOL_RESULT
├─ id
├─ versionId              可选
├─ locator                可选的结构化定位信息
└─ contentHash            可选
```

`locator` 不应只保存自由文本；文档来源应尽量包含页码、块 ID、段落或偏移位置。资源发生版本变化时，旧来源不得静默指向新内容。

## 9. 事实、决定与来源规则

### 9.1 confirmed 不是模型自由判断

- 用户明确、无歧义地陈述的会话事实可以标记为 `confirmed`；
- 文档事实只有在存在可定位文档来源时才能标记为 `confirmed`；
- 工具结果只有在工具成功且结果可追溯时才能作为确认来源；
- 助手消息不能单独支持 `confirmed fact`；
- 模型建议只能形成 `proposed decision`，用户明确确认或确定性业务事件发生后才能成为 `confirmed decision`；
- “重复出现”只能提示信息可能重要，不能替代用户确认或材料证据；
- V1 不保存或展示 LLM 自报的伪精确 `confidence`。

### 9.2 审查结论不是普通事实

未来专业审查中的确定性发现不能仅以 `fact` 表示。奖学金审查结论仍必须同时关联规则依据和材料证据；任一不足时进入人工确认。Memory V1 不新增或弱化该规则。

## 10. 上下文组装契约

`MemoryContextBuilder` 是模型上下文的唯一组装入口。不得由规划器、直接回答或最终合成各自读取 Redis Map 并自由拼接。

每次模型调用按用途从以下受控区块选择：

```text
System / Business Policy
Current User Message
Current Runtime State（仅必要字段）
Structured Conversation Summary
Recent Raw Messages after summary coverage
Current Trace Required Tool Results
```

行为不变量：

1. 当前用户消息只出现一次；
2. `sequence <= coverage.toSeq` 的消息不再作为近期原文重复注入；
3. `sequence > coverage.toSeq` 的有效消息不得遗漏；
4. 工具调用与对应工具结果必须成对保留，不能从中间截断；
5. token 预算按模型能力和调用阶段配置，不冻结“最近 5 轮”或“每 20 轮摘要”等经验数字；
6. 约束、事实、决定和来源以带边界标签的紧凑文本渲染，不把存储 JSON 原样塞给模型；
7. 文档、网页、工具结果和派生记忆属于不可信数据，不能覆盖 System Policy；
8. 记忆不得覆盖当前文档版本、当前用户明确修正或权威业务状态；
9. 所有模型调用路径必须接受同一份上下文契约，具体区块预算可以不同。

## 11. 摘要更新契约

- 允许为摘要生成额外调用模型；摘要调用与主回答调用分离计量、超时和降级；
- Memory V1 不调用 Embedding。允许使用 Embedding 是后续扩展权限，不是 V1 依赖或交付要求；
- 触发条件以 token 预算为主，不使用固定轮数作为唯一条件；
- 输入为“上一版本摘要 + 尚未覆盖的连续消息”；
- 输出必须通过 JSON Schema、状态枚举和来源引用校验；
- 对仍有效的旧条目采用保留、更新、替代或关闭，不允许无理由删除；
- 摘要生成失败时继续使用上一版本摘要和原始消息，不能破坏主对话；
- 摘要是可重新生成的派生数据，不是原始事实主库；
- 摘要任务必须幂等，并通过 `summaryVersion` 防止并发覆盖。

## 12. 存储与生命周期契约

- MySQL 是 Conversation、Message 和 Summary 的持久化事实主库，Redis 不得作为这些数据的唯一副本；
- V1 持久化表使用独立命名空间：`ai_conversation`、`ai_conversation_message`、`ai_conversation_summary`，不得复用文档表或向量 Collection；
- 原始 Conversation、Message 和 Summary 默认长期保留；
- Redis 只承担近期缓存、运行状态和必要锁，不作为唯一长期事实来源；
- 消息追加与 sequence 分配必须在同一数据库事务内完成；`conversation_id + sequence` 必须唯一；
- 数据库结构变更必须幂等、可在已有 `omnidoc` 数据库升级，不得删除、重命名或静默改写现有非 Memory 表；
- 用户删除 Conversation 时，必须级联删除或不可逆失效其消息、摘要、缓存和未来可能存在的向量派生数据；
- “归档”与“删除”必须区分；归档仍可恢复和读取，删除不应继续被模型召回；
- Workspace/User Memory 上线前必须另行冻结保留、修改、遗忘和用户可见性规则。

## 13. Qdrant 边界

Memory V1 不依赖 Qdrant。

未来语义记忆可以复用供应商无关的 `VectorBackend`、作用域过滤、Shadow、迁移和回滚能力，但应通过独立的 `MemoryVectorIndex` 与独立逻辑语料空间接入。不得把聊天记忆直接混入 `omnidoc_chunks_active` 文档片段集合，也不得把 Qdrant 作为记忆事实主库。

## 14. 安全与隐私不变量

- 每次读写必须校验 Conversation 所有者；
- 日志不得记录完整对话、文档正文、Embedding、令牌或认证信息；
- 密钥、审批令牌和敏感运行参数不得进入摘要；
- 文档或网页中的文本不能被提取为用户偏好、系统策略或高优先级指令；
- 删除请求必须覆盖持久化数据、缓存和派生索引；
- V1 不声称完成生产加密、合规、备份或灾备，相关能力需要独立验收。

## 15. 验收原则

质量门槛按以下优先级冻结：

1. `P0 事实准确性`：摘要不得无来源新增事实，不得把助手建议升级为确认事实，用户修正和来源版本变化必须可追踪；
2. `P0 隔离与安全`：用户、Conversation、Runtime State 与不可信内容之间不得串扰或越权；
3. `P1 连续性与完整性`：长期目标、约束、决定和待办不得因窗口压缩静默丢失；
4. `P1 相关性`：只注入当前调用需要的记忆，避免旧文档、旧选区和无关工具结果污染；
5. `P2 效率`：在前四项门槛满足后，再比较 token、摘要调用成本、延迟和存储开销。

Memory V1 至少需要证明：

- 多轮指代能够使用先前消息作答；
- Planner、Direct Answer、Reflection 和 Final Synthesis 不再各自遗漏历史；
- 长对话中摘要与近期消息无重复、无缺口；
- 摘要不会把助手建议晋升为已确认事实或决定；
- 旧文档、旧选区和旧工具状态不会污染新对话；
- 服务重启后长期会话仍可读取；
- 并发追加消息不会丢失或覆盖；
- 删除 Conversation 后其消息、摘要、缓存和派生数据均不可召回；
- 现有 Agent、RAG、MCP、Qdrant 和文档能力没有回归；
- 未实现的 Workspace/User Memory 不会被描述为已可用。
