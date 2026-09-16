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

## T03 实施交接

- 状态：`HANDOFF_READY`
- 分支：`feature/mcp-search-agent-routing`
- 基线：`61266dbbe268958c1e4d7ccbea9ccc5f15220ad8`
- 提交：以本文件所在分支的交接 HEAD 为准

### 已完成

- 保持原生工具既有 `switch` 分发不变，在其前仅为 `mcp.*` 增加 `McpToolGateway` 路由；
- Planner、补充规划和反思提示加入 MCP/RAG/禁网规则，且执行器对规划结果再次执行确定性路由约束；
- 明确实时、最新、当前、网页或联网意图可选择已注册的 `mcp-search`，上传材料、知识库和已有文档意图优先 RAG，明确禁网时从工具目录和执行计划中屏蔽 MCP；
- MCP 调用前先检查网关支持状态，再复用现有参数校验和 `action_required`/继续任务机制；
- 新增 MCP 结果适配器，优先读取 `structuredContent`，兼容文本/JSON 回退，输出 `query/results/sources/mcp`，对 URL 去重并拒绝非 HTTP(S) 来源；
- 使用 `agent.mcp.servers.web-search.max-result-chars`（默认 30000）限制原始 MCP 结果进入 Agent 上下文，超限返回 `MCP_RESULT_TOO_LARGE`；
- 最终答案移除不在实际 `sources` 中的 URL，并补齐实际来源；空结果给出明确提示；MCP 失败返回标准错误语义且不声称取得实时结果；
- Timeline Trace 包含 MCP Server、远程工具、暴露工具、状态、耗时和错误码；认证失败等不可重试错误不会被现有重试策略重复调用。

### 修改文件

- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/AgentExecutionService.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/reflection/AgentReflectionService.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/mcp/McpToolResultAdapter.java`
- `ai-service/src/test/java/com/javaee/aiservice/agent/execution/McpAgentRoutingTest.java`
- `docs/mcp-search/handoffs/T03-agent-routing.md`
- `docs/PROGRESS.md` 的 T03 区域

### 实际验证

- `.\mvnw.cmd -pl ai-service -am -DskipTests compile`：通过；`common` 与 `ai-service` 均 `BUILD SUCCESS`。
- `.\mvnw.cmd -pl ai-service -am "-Dtest=McpAgentRoutingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：10/10 通过。
- `.\mvnw.cmd -pl ai-service -am "-Dtest=AgentExecutionServiceTest,AgentControllerTest,McpContractTest,McpAgentRoutingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：45/45 通过，其中原有 Agent/Controller 基线 32/32、冻结契约 3/3、T03 新增 10/10。
- `git diff --check`：通过，无空白错误。

### 关键用例

- 外部实时问题在已注册 MCP 搜索工具存在时选择 MCP；
- 内部已上传材料问题将模型选择的 MCP 改路由到 `rag-answer`；
- 明确禁网任务从目录和计划中屏蔽 MCP；
- MCP 缺少必填参数进入现有 `action_required`，不调用网关；
- structured content 优先于文本内容，文本内容可回退，来源按原顺序去重；
- 超大结果在进入 Agent 上下文前被拒绝；
- 成功结果参与最终合成，虚构 URL 被移除且实际来源进入答案与 Trace；
- 空结果有明确提示；认证失败保留错误码、脱敏消息、Trace，并且不重试、不声称获得最新信息；
- 原生 Agent/Controller 32 项用例无回归。

### 未验证项与风险

- T01/T02 尚在独立开发，本任务只使用 T00 冻结 contract 和 fake gateway；动态目录、真实连接层合并后的编译与联调未验证；
- 未启动完整服务，未执行真实模型、真实 Tavily MCP 或端到端远程搜索；
- 结果归一化已覆盖冻结契约中的常见 `structuredContent`、JSON 文本和纯文本形态，真实供应商若返回新的嵌套结构，仍需在集成阶段补充 fixture；
- MVP 按冻结范围使用单一 `web-search` Server 的结果大小配置，多 Server 的独立限制不在 T03 范围内。
