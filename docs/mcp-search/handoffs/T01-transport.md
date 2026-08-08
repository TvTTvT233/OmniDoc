# T01 交接任务：MCP 连接与协议层

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

使用 MCP Java SDK 2.0.0 实现供应商无关的 Streamable HTTP Client，连接 Tavily Remote MCP，完成初始化、白名单工具发现和工具调用。

## 允许修改

- 根 `pom.xml` 中 MCP BOM；
- `ai-service/pom.xml`；
- `agent/execution/mcp` 下连接、配置、生命周期代码；
- `application.yml` 中已冻结的 MCP 配置；
- T01 独立测试；
- 本文件和 `PROGRESS.md` 的 T01 区域。

## 禁止修改

- 已冻结的 `mcp.contract`，除非先请求主协调任务变更；
- `AgentToolRegistry`；
- `AgentExecutionService`；
- Planner、RAG 和最终回答逻辑；
- 用户进入任务前已有的无关修改。

## 必须实现

- `mcp-core` + `mcp-json-jackson2`；
- Sync Client；
- JDK HttpClient Streamable HTTP Transport；
- Authorization Bearer Header；
- 初始化和 `tools/list`；
- 处理 `tools/list` 分页直到没有 `nextCursor`；
- allowlist 过滤；
- `tools/call`；
- 连接/请求超时；
- 启动 fail-open；
- 优雅关闭；
- 错误映射为 `McpErrorCode`；
- 日志和异常密钥脱敏。

## 自验收

- 模拟 Server 初始化成功；
- 仅返回 allowlist 工具；
- 调用参数原样传递；
- 文本和 structured content 均保留；
- 401 -> `MCP_AUTH_FAILED`；
- 超时 -> `MCP_TIMEOUT`；
- 断网 -> `MCP_UNAVAILABLE`；
- 不可用时 `ai-service` 可以启动；
- MCP 关闭时不创建 Client。

## 交付格式

- 状态：`HANDOFF_READY` 或 `BLOCKED`；
- 分支与提交 ID；
- 修改文件；
- 实际测试命令和结果；
- 未验证项；
- 依赖冲突和剩余风险；
- 不得将真实 Key 写入交接文档。
