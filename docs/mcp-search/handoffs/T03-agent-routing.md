# T03 交接任务：Agent 路由、执行与结果合成

## 开始前必读

1. `AGENTS.md`
2. `docs/OMNIDOC_CONTEXT.md`
3. `docs/requirements/SCHOLARSHIP_REVIEW_V1.md`
4. `docs/PROGRESS.md`
5. `docs/mcp-search/00-GOAL_AND_SCOPE.md`
6. `docs/mcp-search/01-ARCHITECTURE.md`
7. `docs/mcp-search/02-CONTRACT.md`
8. `docs/mcp-search/03-TASK_BOARD.md`

## 目标

让现有 Agent 执行器可以选择和调用已注册 MCP 搜索工具，将结果归一化、加入上下文、用于反思与最终回答，并保留来源与 Trace。

## 允许修改

- `AgentExecutionService`；
- 必要的 `AgentReflectionService`；
- MCP -> `AgentToolResult` adapter；
- T03 独立路由和结果测试；
- 本文件和 `PROGRESS.md` 的 T03 区域。

## 禁止修改

- 已冻结的 `mcp.contract`，除非先请求主协调任务变更；
- MCP SDK/POM；
- HTTP Client/Transport；
- `AgentToolRegistry` 的结构性重构；
- 与 MCP 无关的现有工具实现。

## 必须实现

- 原生工具继续走现有分发；
- `mcp.*` 工具通过 `McpToolGateway` 调用；
- MCP 参数复用现有 action-required 机制；
- 最新/当前/网页/联网意图允许外部搜索；
- 上传文档/知识库意图优先内部 RAG；
- 明确禁止联网时屏蔽 MCP；
- structured content 优先，文本回退；
- 标准结果包含 query/results/sources/mcp；
- 上下文限制结果大小；
- 最终回答仅引用实际 sources；
- Trace 包含 Server、工具、状态、耗时、错误码；
- MCP 失败不声称获得实时结果。

## 自验收

- 外部实时问题选择 MCP；
- 内部材料问题选择 RAG；
- 禁止联网任务不选 MCP；
- MCP success 进入最终回答；
- 空结果有明确提示；
- MCP error 进入现有失败/反思链路；
- 现有 32 个 Agent/Controller 基线测试不回归；
- 原生工具分发不变。

## 交付格式

- 状态：`HANDOFF_READY` 或 `BLOCKED`；
- 分支与提交 ID；
- 修改文件；
- 实际测试命令和结果；
- 关键路由用例；
- 未验证项和剩余风险。
