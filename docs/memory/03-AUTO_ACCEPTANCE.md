# OmniDoc Memory V1：自动验收门禁

## 1. 当前状态

```text
PENDING_IMPLEMENTATION
```

本文件冻结 M07 的自动验收条件，不声明当前实现已经通过。最终应提供统一入口和机器报告；任何硬门禁失败、缺失、跳过或只做静态检查时，状态都不得写为 `AUTO_VERIFIED`。

验收状态按以下顺序推进：

```text
PENDING_IMPLEMENTATION
  -> ASSET_VERIFIED / PENDING_INTEGRATION
  -> AUTO_VERIFIED / MANUAL_PENDING
```

`ASSET_VERIFIED` 只证明 fixture、JSON Schema、报告 Schema、静态安全检查和验收入口自检通过。生产实现缺失时入口必须明确返回 `PENDING_INTEGRATION`，不得因为资产自检成功而退出为最终通过。

## 2. 统一入口与报告

计划入口：

```powershell
.\scripts\memory\run-m07-acceptance.cmd -Integrated
```

计划报告：

```text
ai-service/target/memory-v1-acceptance/memory-v1-report.json
```

报告至少包含：Git 提交、时间、操作系统、Java/Node 版本、测试分组、通过/失败/跳过数量、MySQL/Redis/模型边界、token 配置、摘要调用统计、隔离计数、删除残留和回归结果。未连接真实模型时必须记录 `realModel=false`。

## 3. P0 硬门禁

### A. 持久化与顺序

- MySQL 中 Conversation、Message、Summary 表结构与索引存在；
- 创建、分页读取、归档、恢复和删除语义符合契约；
- 服务实例重建后仍能读取原始消息；
- 同一 Conversation 并发追加不丢消息、不覆盖，`conversationId + sequence` 重复数为 0；
- 原始消息不因 Redis 24 小时 TTL 或缓存清理丢失；
- Redis 不可用时行为符合冻结的失败/降级语义，不能静默伪造空历史。

### B. 事实准确性与摘要

- 无来源内容不能进入 `confirmed fact`；
- 助手建议不能单独进入 `confirmed fact/decision`；
- 用户明确修正后旧事实进入 `superseded/conflicting`，不继续主导回答；
- `coverage` 连续，无重复、无缺口；
- Summary Item 的状态、SourceRef、schemaVersion 和 sourceDigest 校验通过；
- 并发摘要旧版本不能覆盖新版本；
- 摘要失败不推进 coverage，不破坏原始消息和上一版摘要；
- 日志和报告中不出现完整会话、文档正文、密钥、认证 Header 或 Embedding。

### C. 隔离与删除

- 跨用户 Conversation 读取、写入、归档、恢复和删除成功数为 0；
- Conversation A 的消息、摘要、Runtime State 不进入 Conversation B；
- 新对话不继承旧文档、选区、审批令牌或完整工具结果；
- 删除后 MySQL 可召回消息数、Summary 数、Redis 缓存键数和 Runtime State 键数均为 0；
- 归档仍可恢复，且与删除状态清晰区分。

### D. 上下文组装

- Planner、Direct Answer、Reflection、完成度判断和 Final Synthesis 均消费统一 `MemoryContextBuilder` 契约；
- 当前消息只出现一次；
- `sequence <= coverage.toSeq` 不作为近期原文重复注入；
- `sequence > coverage.toSeq` 的预算内有效消息不遗漏；
- Tool Call 与 Tool Result 成对保留；
- 上下文始终在配置 token 预算内，超预算有可解释裁剪原因；
- 不可信文档/网页/工具/记忆内容不能覆盖 System/Business Policy。

### E. 授权与 API

- 所有 Conversation 接口只信任认证用户上下文；
- 客户端 userId 参数或请求体不能改变所有者；
- 不存在、无权访问、已删除、无效游标和并发冲突具有稳定错误语义；
- 旧 chat start/chat/history/end 入口的兼容测试通过；
- 结构化 API 不泄露内部 System Prompt、审批令牌和完整工具结果。

## 4. P1 功能门禁

- 短对话中的代词和“刚才那个”能够使用近期历史回答；
- 长对话在压缩后仍能召回早期目标、约束、确认事实、决定和待办；
- 每个模型调用阶段可以使用不同区块预算，但遵循同一语义边界；
- 摘要生成允许额外模型调用，并有独立超时、重试、成本和失败计数；
- Memory V1 全流程不调用 Embedding，不创建 Memory Qdrant Collection；
- 前端刷新后按 `conversationId` 恢复结构化历史；
- “新对话”创建新的后端 Conversation，不再生成临时 ID 或复用 `default_conv`；
- 全局助理与编辑器对话入口共享相同会话语义；
- 历史分页、归档、恢复和永久删除的 UI 状态与后端一致。

## 5. 回归门禁

至少覆盖：

- Memory 专项单元、契约和集成测试；
- `AgentExecutionServiceTest`；
- `AgentControllerTest`；
- RAG、MCP、Qdrant 的既有专项回归入口；
- 完整 `ai-service` 测试；
- `web` 自动测试和生产构建；
- Git 可见文本敏感信息静态检查。

最终报告中 Memory 目标测试不得存在 skipped。外部真实模型、真实 Tavily 或真实远程 Embedding 未运行时应单独记录，不得让本地占位配置伪装成成功。

## 6. 指标记录

在 P0/P1 通过后记录但暂不冻结生产 SLA：

- 每次模型调用的上下文字符数、估算 token 和各区块占比；
- 摘要触发次数、成功率、重试数、耗时和估算成本；
- Conversation/Message/Summary 存储量；
- 短对话和长对话 P50/P95；
- 历史分页 P50/P95；
- 删除级联耗时；
- Redis 命中/未命中与 MySQL 回源次数。

这些本机合成指标只用于发现回归，不等同于生产容量结论。

## 7. 自动结论规则

只有同时满足以下条件才能标记 `AUTO_VERIFIED`：

- 所有 P0 和 P1 用例通过；
- Memory 目标测试 skipped = 0；
- 既有回归无新增失败；
- 机器报告字段完整且与测试结果一致；
- 测试派生数据已清理；
- 未把真实模型或外部服务的未执行状态写成通过。

自动通过后状态只能进入：

```text
AUTO_VERIFIED / MANUAL_PENDING
```
