# OmniDoc Memory V1：实现任务看板

## 1. 当前状态

```text
CONCEPT_FROZEN / M01_BASELINE_VERIFIED / M02_STAGE_VERIFIED / M03_STAGE_VERIFIED / M04_PENDING
```

Memory V1 保留三级记忆架构，只实现 Conversation Memory。Workspace Memory 和 User Memory 不属于本看板的实现范围。

## 2. 状态定义

- `TODO`：尚未开始；
- `IN_PROGRESS`：正在实施；
- `BLOCKED`：存在已确认阻塞；
- `HANDOFF_READY`：专项任务已完成自测并可交接；
- `MERGED`：已经集成；
- `AUTO_VERIFIED`：自动验收通过；
- `HUMAN_ACCEPTED`：用户人工验收通过；
- `FROZEN`：概念或契约已经冻结，但不代表实现完成。
- `BASELINE_VERIFIED`：当前行为、已知缺口和验收资产已完成集成验证，但目标生产能力仍未实现。
- `STAGE_VERIFIED`：该阶段的冻结目标与回归门禁已在本地确定性环境通过，但不代表真实外部服务、最终 M07 或人工验收完成。

## 3. 任务总览

| 编号 | 任务 | 状态 | 依赖 | 主要交付 |
| --- | --- | --- | --- | --- |
| M00 | 目标、作用域与契约冻结 | `FROZEN` | 无 | `00-MEMORY_V1_SPEC.md` |
| M01 | 当前行为基线与验收夹具 | `BASELINE_VERIFIED` | M00 | 缺口测试、确定性会话 fixture、容量基线 |
| M02 | 持久化 Conversation 与结构化 Message | `STAGE_VERIFIED` | M01 | 数据表、Repository、单调序列、长期保留 |
| M03 | Runtime State 分离与生命周期 | `STAGE_VERIFIED` | M02 | 短期状态模型、TTL、级联删除、兼容读取 |
| M04 | 统一 MemoryContextBuilder 与 Agent 接入 | `TODO` | M02、M03 | token 预算、近期历史、全模型路径接入 |
| M05 | Structured Rolling Summary | `TODO` | M04 | Summary Schema、更新器、版本控制、回退 |
| M06 | 前端历史、新对话与删除体验 | `IN_PROGRESS (M06-A STAGE_VERIFIED; M06-B BACKEND VERIFIED)` | M02、M03、M05 | 历史恢复、真正新会话、归档/删除入口 |
| M07 | 独立自动验收与人工验收 | `IN_PROGRESS` | M01-M06 | 回归、质量门禁、测试报告、人工验收文档 |

## 4. 实施顺序与边界

### M01：当前行为基线与验收夹具

目标：先证明当前缺口，并建立后续实现不能破坏的确定性基线。

必须覆盖：

- 历史消息已保存但 Direct Answer 未读取的复现；
- Planner、Reflection 和 Final Synthesis 当前上下文输入差异；
- Redis `conv:*` 与 `ctx:*` 生命周期不一致；
- 前端刷新不恢复消息、“新对话”只清空 UI 的现状；
- 并发追加消息和上下文更新风险；
- 长消息下按条数截断而非 token 截断的现状；
- 现有 Agent、RAG、MCP、Qdrant 直接相关回归基线。

禁止：

- 在基线任务中顺手重构生产实现；
- 把静态代码检查写成真实模型或完整服务已验证。

### M02：持久化 Conversation 与结构化 Message

目标：建立长期、可排序、可审计的会话事实底稿。

必须完成：

- Conversation 与 Message 持久化模型；
- 对话内单调递增 `sequence`；
- `USER/ASSISTANT/TOOL/SYSTEM` 结构化角色；
- 幂等追加和并发安全；
- 所有者校验；
- 分页读取和按 sequence 读取；
- 原始消息长期保留；
- 现有 `conversationId` API 兼容策略；
- 明确旧 Redis 会话的读取、过渡和到期策略。

不包含：

- 摘要；
- Qdrant；
- Workspace/User Memory。

### M03：Runtime State 分离与生命周期

目标：停止把任意 Redis `ctx:*` Map 当成长期记忆。

必须完成：

- 定义受控 Runtime State 字段；
- 当前文档、选区、页面、审批和工具状态分别设置生命周期；
- Conversation 删除时清理消息之外的缓存和运行状态；
- 防止旧 `documentId`、`selectedText`、`lastToolResults` 污染后续任务；
- 归档与删除语义分离；
- Redis 不可用时的明确失败或降级语义。

不包含：

- 把 Runtime State 自动提升为长期记忆；
- 用户偏好提取。

### M04：统一 MemoryContextBuilder 与 Agent 接入

目标：建立所有模型调用共同遵守的上下文入口。

必须完成：

- 统一上下文数据结构和渲染器；
- 当前消息、近期历史、运行状态和当前 Trace 工具结果的预算；
- 按模型上下文窗口配置 token 预算；
- 工具调用与结果成组保留；
- Planner、Direct Answer、Reflection、完成度评估和 Final Synthesis 接入；
- 上下文来源、大小、裁剪原因的脱敏 Trace；
- 兼容现有 RAG/MCP 引用和不可信数据边界。

不包含：

- Rolling Summary；
- 语义历史召回。

### M05：Structured Rolling Summary

目标：在长对话超出近期窗口时保留稳定会话状态。

必须完成：

- 实现冻结的 Summary Schema 和 JSON 校验；
- token 阈值触发；
- “上一版本摘要 + 未覆盖连续消息”滚动更新；
- `coverage + sourceDigest + summaryVersion`；
- 乐观并发控制和幂等重试；
- facts、decisions、todos 等状态转换规则；
- 来源引用校验；
- 失败时保留旧摘要、不推进 coverage；
- 存储 JSON 与模型渲染格式分离。

安全门禁：

- 助手建议不得自动变成 confirmed decision；
- 无来源内容不得变成 confirmed fact；
- 密钥、令牌、选区和完整工具结果不得进入摘要；
- 文档中的提示文本不得成为系统约束。

### M06：前端历史、新对话与删除体验

目标：让前端展示状态与后端真实会话一致。

必须完成：

- 刷新后按 `conversationId` 恢复历史；
- “新对话”创建新的后端 Conversation；
- 无效、过期或无权限 Conversation ID 的恢复流程；
- 归档与删除交互；
- 删除后的本地缓存清理；
- 长历史分页加载；
- 全局助手与编辑器使用一致的会话语义。

不包含：

- “我的记忆”管理页面；
- Workspace/User Memory UI。

### M07：独立自动验收与人工验收

自动验收至少包括：

- 最近多轮指代；
- 摘要覆盖后的远距离事实召回；
- 摘要覆盖无重复、无缺口；
- proposed/confirmed 状态不漂移；
- 相互矛盾事实保持 conflicting/superseded；
- 并发消息顺序与摘要 CAS；
- 重启后长期历史恢复；
- 归档可恢复、删除不可召回；
- 跨用户访问为零；
- Runtime State 不进入长期摘要；
- 上下文始终处于 token 预算内；
- Agent、RAG、MCP、Qdrant 和前端关键回归。

人工验收至少包括：

- 在真实模型下完成一段短对话和一段长对话；
- 刷新、重新登录和次日继续对话；
- 新建对话不继承旧文档和选区；
- 用户修正事实后旧摘要不继续主导回答；
- 删除会话后无法通过历史、缓存或检索恢复；
- 记录真实模型、真实服务和实际测试材料边界。

## 5. 公共实施规则

- 先完成 M01 基线，再修改生产实现；
- M02-M05 按依赖顺序推进，不提前接入 Qdrant；
- 不将 Workspace/User Memory 顺手塞入 Conversation Memory 任务；
- 不把 `knowledgeBaseId` 固化为未来 Workspace 的唯一标识；
- 原始消息是主数据，摘要始终是可重建派生数据；
- 所有新增配置必须提供安全默认值和本地配置示例；
- 每项任务记录真实执行的测试，未连接真实模型或外部服务时必须明确说明；
- 实现阶段发现冻结契约缺口时，先更新 M00 决策记录，不由专项任务静默改变语义。

API、存储和运行语义以 `02-API_AND_STORAGE_CONTRACT.md` 为准；并行开发的分支、文件所有权、波次和集成顺序以 `02-PARALLEL_DELIVERY_PLAN.md` 为准；自动与人工验收分别以 `03-AUTO_ACCEPTANCE.md`、`04-HUMAN_ACCEPTANCE.md` 为准。

## 6. 后续版本预留

以下能力必须在 Memory V1 验收之后重新冻结契约：

- Workspace 实体与 Workspace Memory；
- 最小 User Preference Memory；
- 记忆候选、确认、修改和遗忘；
- `MemoryVectorIndex` 与独立 Qdrant 语料空间；
- 跨会话语义召回；
- 专业审查场景的规则、材料证据和人工确认记忆。
