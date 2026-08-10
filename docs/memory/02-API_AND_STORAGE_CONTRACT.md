# OmniDoc Memory V1：API、存储与运行契约

## 1. 状态

```text
FROZEN / NOT_IMPLEMENTED
```

本文件补齐并行实现需要共享的技术语义。它不代表数据库表、API、迁移或前端已经存在。

## 2. 组件所有权与技术选择

- Conversation Memory 归 `ai-service` 所有，不新建独立微服务；
- MySQL `omnidoc` 数据库是 Conversation、Message、Summary 的事实主库；
- V1 使用 Spring JDBC 访问持久化数据，不引入新的通用 ORM 抽象；
- Redis 只用于 Runtime State、缓存、短锁和兼容读取，不是长期事实主库；
- 数据库结构使用 Memory 专属、版本化、幂等 SQL 和 `MemorySchemaInitializer`；
- 自动建表必须由 `ai.memory.schema.auto-migration.enabled` 显式控制；关闭时表不存在必须启动失败并给出明确错误，不得退回 Redis 伪装为长期存储；
- SQL 不得删除、重命名或修改非 `ai_memory_schema_version`、`ai_conversation*` 表；
- V1 不新增 `scope_type/scope_id` 通用记忆表。三级作用域是稳定领域边界，Workspace/User 上线时通过新适配器扩展，不把未来抽象提前塞进 Conversation 表。

需要新增的运行依赖限定为 Spring JDBC 与 MySQL Driver；依赖启用后必须修复测试配置，不能让无 MySQL 的单元测试被错误变成外部集成测试。

## 3. 数据表契约

### 3.1 `ai_conversation`

至少包含：

```text
conversation_id        VARCHAR(64) PK
user_id                VARCHAR(128) NOT NULL
title                   VARCHAR(255)
status                  ACTIVE / ARCHIVED
next_sequence           BIGINT NOT NULL
created_at              DATETIME(3) NOT NULL
updated_at              DATETIME(3) NOT NULL
archived_at             DATETIME(3) NULL
version                 BIGINT NOT NULL
```

索引至少支持：

- `user_id + status + updated_at + conversation_id` 稳定列表分页；
- 所有者加主键读取；
- 乐观版本更新。

`DELETED` 是删除事务中的生命周期事件，不保留为可召回的长期行。删除完成后公共 API 按不存在处理。

### 3.2 `ai_conversation_message`

至少包含：

```text
message_id              VARCHAR(64) PK
conversation_id         VARCHAR(64) NOT NULL
sequence                BIGINT NOT NULL
turn_id                 VARCHAR(64) NOT NULL
client_request_id       VARCHAR(128) NOT NULL
role                    SYSTEM / USER / ASSISTANT / TOOL
message_type            TEXT / TOOL_CALL / TOOL_RESULT / EVENT
content                 LONGTEXT
trace_id                VARCHAR(64) NULL
tool_call_id            VARCHAR(128) NULL
metadata_json           JSON/TEXT NULL
content_hash            CHAR(64) NOT NULL
created_at              DATETIME(3) NOT NULL
```

约束：

- `conversation_id + sequence` 唯一；
- `conversation_id + client_request_id + role + message_type + turn 内 ordinal` 幂等；
- Message 通过外键或等价的事务级联语义归属 Conversation；
- Tool Call/Tool Result 使用相同 `tool_call_id`，不得拆对；
- `metadata_json` 只允许白名单字段，不保存认证 Header、审批令牌、完整异常栈或未限长工具结果。

### 3.3 `ai_conversation_summary`

至少包含：

```text
conversation_id         VARCHAR(64) PK
schema_version          VARCHAR(16) NOT NULL
summary_version         BIGINT NOT NULL
from_sequence           BIGINT NOT NULL
to_sequence             BIGINT NOT NULL
source_digest           CHAR(64) NOT NULL
summary_json            LONGTEXT NOT NULL
generated_at            DATETIME(3) NOT NULL
updated_at              DATETIME(3) NOT NULL
```

Summary 与 Conversation 一对一保存当前有效版本；如后续需要版本历史，另建派生审计表，不改变当前读取语义。

## 4. 消息提交与幂等

- 每次用户发送由前端生成 UUID `clientRequestId`；异步重试必须复用同一值；
- 后端为一次 Agent 执行生成或复用 `turnId`，并将其关联 trace；
- USER 消息在开始模型调用前提交，避免模型失败时丢失用户输入；
- ASSISTANT、TOOL_CALL、TOOL_RESULT 和 EVENT 在产生时分别追加，不以读改写整个历史实现；
- 模型失败时保留 USER 消息，并追加不含敏感异常的失败 EVENT；不得生成伪助手成功回答；
- 相同 `clientRequestId` 重试不得重复写入同一 USER 消息或已提交的工具/助手消息；
- sequence 分配与消息插入在同一 MySQL 事务内完成，通过锁定 Conversation 行或等价原子操作保证单调、唯一；
- 归档 Conversation 禁止追加，必须先恢复；
- 所有追加操作先验证认证用户为所有者。

旧客户端未提供 `clientRequestId` 时由服务端生成，只保证单次服务调用内部幂等；前端 M06 上线后必须始终提供该字段。

## 5. 旧 Redis 会话兼容

- 新建 Conversation 和新消息只写 MySQL；可写近期缓存，但不得双写旧字符串列表作为事实源；
- 对上线前仍存在的 `conv:*`，在明确配置的兼容窗口内进行所有者校验后的按需一次性导入；
- 只识别严格的 `User: ` 与 `Assistant: ` 前缀，按原列表顺序生成 sequence；无法可靠解析的条目作为 `EVENT` 保留原文并标记 `legacyUnparsed=true`，不猜测角色；
- 旧结构没有可靠消息时间和 trace，导入时必须在 metadata 标记 `legacyImported=true`，不得伪造精确来源；
- `ctx:*` 不导入 Conversation Summary；M03 只按 Runtime State 白名单短期兼容读取；
- 兼容导入幂等，同一旧 Conversation 不得重复生成消息；
- 旧 Redis 数据已经自然过期时不承诺恢复，不把“未找到”描述为迁移成功；
- 兼容窗口结束前记录真实迁移数量，结束后移除旧写入路径。

## 6. Runtime State 契约

允许的类别和默认生命周期：

| 类别 | 字段示例 | 生命周期 |
| --- | --- | --- |
| 单次输入 | `selectedText`, `pageContext`, `documentContent` | 当前 turn 消费后删除，硬上限 10 分钟 |
| 页面定位 | `currentDocumentId`, `route` | 滑动 30 分钟，客户端每次请求重新声明 |
| Agent 续接 | `traceId`, `currentToolState` | 最长 1 小时或任务结束后删除 |
| 审批 | `pendingApproval` 的非敏感引用 | 不超过审批令牌有效期，默认 5 分钟 |

规则：

- 完整审批令牌不得写入通用 Runtime State；
- `lastAnswer`、完整 `lastToolResults`、完整 `lastReflections` 不再作为跨 turn 任意 Context 保存；
- Runtime State API 必须校验 Conversation 所有者；
- Redis 不可用时，普通新对话可在没有 Runtime State 的情况下继续；需要审批续接或依赖一次性选区的操作明确失败，不猜测状态；
- Conversation 删除时清理 Runtime State、关联 Agent Task Snapshot 和待审批引用；
- `continueTraceId` 必须在 Service 内再次校验 trace、Conversation 和用户三者归属，不能只依赖 Controller；
- 审批取消必须先校验所有者，再使令牌失效。

## 7. Summary 可执行契约

- Summary JSON 必须匹配 `00-MEMORY_V1_SPEC.md` 的字段、枚举和来源规则；M01 以 JSON Schema 形成机器契约；
- `contentHash = SHA-256(UTF-8 原始 content)`；
- `sourceDigest` 使用以下规范串的 UTF-8 SHA-256：

```text
conversationId + "\\n" +
fromSeq + "\\n" +
toSeq + "\\n" +
for each message:
  sequence + "|" + messageId + "|" + role + "|" + messageType + "|" + contentHash + "\\n"
```

消息按 sequence 升序；字段不做本地化转换；最后一行保留换行。任何源消息变化都必须产生不同 digest。

- 更新输入只能是上一版有效 Summary 与紧接 coverage 后的连续消息；
- 更新提交使用 `conversationId + previousSummaryVersion` CAS；
- Schema、状态转换、SourceRef、digest 任一校验失败时不提交、不推进 coverage；
- Summary 模型调用具有独立超时和计量，失败不回滚原始消息，也不阻塞预算仍可容纳的短对话；
- 密钥、认证 Header、审批令牌、完整文档正文、一次性选区和完整工具结果先确定性过滤，再进入摘要模型；
- Summary 不调用 Embedding。

## 8. Token 预算契约

- 提供 `TokenEstimator` 与按模型配置的 `ModelContextBudget`，业务代码不得散落字符截断；
- 未配置模型使用保守默认：总窗口 32768、输出预留 4096、安全预留 10%；这些是应用安全默认值，不是对供应商模型上限的声明；
- 默认估算对 CJK/emoji 按每 Unicode code point 至少 1 token、其他文本按每 4 个字符至少 1 token，并叠加消息/区块开销；可以替换为供应商 tokenizer，但不得低于安全预算；
- 当前用户消息、System/Business Policy 和必要工具对优先级高于 Summary 和旧原始消息；
- 单条当前用户消息本身超过输入预算时返回 `MEMORY_CONTEXT_TOO_LARGE`，不得静默截断或让模型假装看到了全文；原始 USER 消息仍按第 4 节保存并记录失败 EVENT；
- 工具调用与结果作为原子组裁剪；
- 每次组装记录脱敏区块 token 数、裁剪原因和最终预算，不记录完整内容。

## 9. Memory 感知的模型调用范围

当请求具有 Conversation 时，以下调用必须使用统一 Context Contract：

1. Planner；
2. Follow-up Planner；
3. Direct Answer；
4. Reflection；
5. 完成度判断；
6. Final Synthesis；
7. Agent 内部的 RAG Answer。

独立的文档摘要、关键词提取、PPT 生成和不属于 Conversation Agent 的批处理接口不在 M04 的 Memory 注入范围。它们仍必须遵守原有安全和输入边界。

## 10. 新结构化 API

Controller 资源根路径冻结为：

```text
/api/ai/conversations
```

接口：

```text
POST   /api/ai/conversations
GET    /api/ai/conversations?status=&cursor=&limit=
GET    /api/ai/conversations/{conversationId}
GET    /api/ai/conversations/{conversationId}/messages?beforeSequence=&limit=
POST   /api/ai/conversations/{conversationId}/archive
POST   /api/ai/conversations/{conversationId}/restore
DELETE /api/ai/conversations/{conversationId}
```

契约：

- 创建响应至少包含 `conversationId/title/status/createdAt/updatedAt`；
- 列表按 `updatedAt DESC, conversationId DESC` 稳定排序，cursor 编码这两个字段；
- 消息接口默认返回最新一页，页内按 sequence 升序；下一页使用 `beforeSequence`，不使用数组下标；
- UI 默认只展示 USER/ASSISTANT 和经过脱敏的可展示工具事件；SYSTEM Prompt、审批令牌和完整工具结果永不返回；
- `limit` 有配置上限；无效 cursor 不回退到第一页；
- 归档可恢复，归档状态禁止发送；永久删除需要前端二次确认；
- 所有者只取认证上下文；请求参数、请求体或管理员角色都不能在 V1 绕过所有者边界；
- 对不存在、已删除和不属于当前用户的 ID，公共 API 统一返回 `CONVERSATION_NOT_FOUND`，避免枚举泄露；
- 其他稳定错误至少包括 `CONVERSATION_ARCHIVED`、`INVALID_CONVERSATION_CURSOR`、`CONVERSATION_CONFLICT`、`MEMORY_CONTEXT_TOO_LARGE`、`RUNTIME_STATE_UNAVAILABLE`。

兼容入口在 V1 保留：

- `POST /api/ai/agent/chat/start` 继续返回 ID 字符串，但内部调用新 Conversation Service；
- `POST /api/ai/agent/chat` 与异步 Agent 入口继续接受 conversationId；
- `GET /api/ai/agent/chat/{id}/history` 继续返回旧字符串列表视图，但数据来自 MySQL；
- `POST /api/ai/agent/chat/{id}/end` 保留永久删除语义并标记废弃；新 UI 不使用它。

## 11. 前端会话导航契约

- 全局助理和编辑器使用同一个 Conversation API、DTO 和历史列表；
- 两个入口各自维护当前活跃指针，避免切换页面时意外串入另一入口的会话；
- 指针按认证用户和入口隔离，不再使用全局固定 `global_conv_id`；
- 编辑器 chat-mode 优先把 conversationId 写入可恢复路由；普通文档页的助理指针不等同于 documentId；
- 用户可以从共享历史列表在任一入口显式打开同一个 Conversation；
- “新对话”必须先成功创建后端 Conversation 再切换 UI；失败时保留当前会话和输入草稿；
- 禁止生成 `temp_*`、使用 `default_conv` 或展示未持久化的伪助手成功回答；
- Agent Workbench 的专用历史 UI 不属于 M06；但其 Agent 请求若创建 Conversation，后端仍按相同持久化和安全契约处理。

## 12. 删除边界

永久删除必须在可审计的编排中完成：

1. 校验所有者；
2. 阻止新的消息追加与摘要提交；
3. 删除/不可逆失效 Message 和 Summary；
4. 删除 Conversation 行；
5. 清理 Redis 缓存和 Runtime State；
6. 清理关联 Agent Task Snapshot 与待审批引用；
7. 记录不含正文的删除结果和残留计数。

任一步失败必须返回明确的部分失败状态并可幂等重试；在清理完成前不得让该 Conversation 继续进入模型上下文。V1 不承诺从既有外部日志、数据库备份或灾备副本执行法务级擦除；日志本身禁止新增完整对话内容。
