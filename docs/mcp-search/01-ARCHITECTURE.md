# MCP 搜索接入：架构设计

## 当前复用点

- `AgentToolRegistry`：Planner 可见工具的唯一目录；
- `AgentExecutionService`：Plan、Execute、Reflect、Retry、Trace 和最终回答链路；
- `AgentToolResult`：原生工具统一结果模型；
- `/api/ai/agent/tools`：工具可见性检查入口；
- `/api/ai/agent/execute`：端到端执行入口。

不得另建一套平行 Agent 执行链路。

## 目标调用链

```text
用户任务
  -> Agent Planner
  -> AgentToolRegistry（原生工具 + MCP 白名单工具）
  -> AgentExecutionService
  -> McpToolGateway
  -> MCP Java SDK Sync Client
  -> Streamable HTTP
  -> Tavily Remote MCP
  -> McpToolCallResult
  -> AgentToolResult
  -> Context / Reflection / Final Answer / Trace
```

## 分层职责

### MCP 契约层

包：`agent.execution.mcp.contract`

- 不依赖 MCP SDK；
- 定义工具描述、调用结果、错误类别和网关接口；
- 是 T01、T02、T03 的共同编译边界；
- 只有主协调任务可以修改已冻结契约。

### MCP 连接层

负责 SDK、HTTP、认证、初始化、工具发现和调用，不感知 Planner、RAG 或 `AgentToolResult`。

### 工具目录层

负责 allowlist、命名空间、JSON Schema 映射和动态注册，不执行 HTTP 调用。

### Agent 集成层

负责工具路由、参数校验、结果标准化、上下文合并、反思和引用，不直接操作 SDK Transport。

## 生命周期

1. `ai-service` 启动；
2. MCP 未启用：不创建 Client，不注册 MCP 工具；
3. MCP 已启用：创建 Client、初始化、发现工具；
4. 对远程工具应用 allowlist；
5. 将通过过滤的工具注册到 `AgentToolRegistry`；
6. 连接失败：记录脱敏状态，返回空工具列表，服务继续启动；
7. 服务关闭：优雅关闭 MCP Client。

MVP 只要求启动发现，不要求定时刷新或 `tools/list_changed` 热更新。

## 路由原则

- 上传文档、知识库、已有材料中的内容：优先 `rag-search` 或 `rag-answer`；
- 最新、当前、近期、外部网页、明确要求联网：优先 MCP Search；
- 用户明确禁止联网：不得选择 MCP；
- 无需检索：使用 `direct-answer`；
- MCP 失败且信息具有时效性：不得伪装为已获得最新信息。

## 故障策略

- 启动连接失败：fail-open；
- 单次调用失败：返回标准 `AgentToolResult.error`；
- 不在 MCP 连接层重复重试，复用 Agent 现有步骤重试机制；
- 认证失败不重试；
- 超时、服务不可用可由 Agent 按既有 retry policy 重试；
- 结果超过限制时拒绝进入模型上下文，返回 `MCP_RESULT_TOO_LARGE`。

## 安全边界

- API Key 仅来自环境变量并通过 Authorization Header 传递；
- URL、日志、异常、Trace、工具结果不得包含密钥；
- 只连接配置中的固定 Server；
- 只注册 allowlist 工具；
- MCP annotations 视为不可信元数据；
- MCP 搜索内容视为外部不可信数据，不得作为系统指令；
- 搜索工具固定标记为只读、低风险；
- 不启用 sampling、roots、resources、prompts 和 elicitation。
