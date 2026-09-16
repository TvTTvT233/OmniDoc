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

## 实施结果

- 状态：`HANDOFF_READY`
- 分支：`feature/mcp-search-transport`
- 提交：本交接文档所在的 T01 提交（实际提交 ID 由最终交付消息提供）

### 已实现

- 根 Maven 依赖管理固定 `io.modelcontextprotocol.sdk:mcp-bom:2.0.0`；`ai-service` 仅引入 `mcp-core` 与 `mcp-json-jackson2`，未引入 Jackson 3 聚合依赖，未升级 Spring AI
- 使用 MCP Java SDK 2.0.0 Sync Client 与 `HttpClientStreamableHttpTransport`，连接超时使用 Transport 配置，请求和初始化超时使用 Sync Client 配置
- 通过 SDK `McpSyncHttpClientRequestCustomizer` 为全部 MCP HTTP 请求设置 `Authorization: Bearer ...`；拒绝在 base URL 或 endpoint 中使用 query、userinfo 或 fragment
- 生命周期启动时完成 initialize 与显式逐页 `tools/list(String cursor)`；检测重复 cursor，按本地 allowlist 过滤，并缓存远程名称到暴露名称的路由
- `tools/call` 原样传递参数，保留 MCP 内容块、`structuredContent` 和非敏感 `_meta`；保留 `isError=true` 的内容并映射为 `MCP_TOOL_ERROR`
- 覆盖 `MCP_DISABLED`、`MCP_UNAVAILABLE`、`MCP_AUTH_FAILED`、`MCP_TIMEOUT`、`MCP_TOOL_NOT_ALLOWED`、`MCP_TOOL_NOT_FOUND`、`MCP_INVALID_ARGUMENTS`、`MCP_TOOL_ERROR`、`MCP_PROTOCOL_ERROR` 与 `MCP_RESULT_TOO_LARGE`
- MCP 默认关闭时不创建 SDK Client；缺失 Key 与远端不可用时默认 fail-open；`fail-fast=true` 保留显式阻断选项；Spring 关闭时调用 SDK `closeGracefully()`
- 对返回内容、结构化内容、元数据、工具描述、Schema、annotations 和公开错误执行递归密钥脱敏；应用关闭 SDK 原始日志，仅保留本地标准化脱敏日志

### 修改文件

- `pom.xml`
- `ai-service/pom.xml`
- `ai-service/src/main/resources/application.yml`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/mcp/McpProperties.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/mcp/McpClientConfiguration.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/mcp/McpSyncClientFactory.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/mcp/SdkMcpSyncClientFactory.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/mcp/SdkMcpToolGateway.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/mcp/McpExceptionMapper.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/mcp/McpSecretSanitizer.java`
- `ai-service/src/test/java/com/javaee/aiservice/agent/execution/mcp/McpClientConfigurationTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/agent/execution/mcp/SdkMcpSyncClientFactoryTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/agent/execution/mcp/SdkMcpToolGatewayIntegrationTest.java`
- `docs/PROGRESS.md` 的 T01 区域
- `docs/mcp-search/handoffs/T01-transport.md`

### 实际验证

- `./mvnw.cmd -pl ai-service -am -DskipTests compile`：通过
- `./mvnw.cmd -pl ai-service -am "-Dtest=SdkMcpToolGatewayIntegrationTest,McpClientConfigurationTest,SdkMcpSyncClientFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：15/15 通过
- `./mvnw.cmd -pl ai-service -am "-Dtest=AgentExecutionServiceTest,AgentControllerTest,McpContractTest,SdkMcpToolGatewayIntegrationTest,McpClientConfigurationTest,SdkMcpSyncClientFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：50/50 通过
- Maven 依赖树：`mcp-core` 与 `mcp-json-jackson2` 均为 2.0.0；Spring AI 仍为 1.0.0-M4；未解析 Jackson 3

### 未验证项与风险

- 未配置真实 Tavily Key，未执行真实 Tavily MCP 冒烟测试
- 未执行依赖 MySQL、Redis、RabbitMQ、Nacos、MinIO 和真实模型的完整 `ai-service` 启动；仅验证 MCP Spring 子上下文在远端不可用时仍能成功启动
- 未执行 T02/T03 动态注册、Agent 路由、结果合成与最终引用；这些内容不属于 T01 文件所有权
- Spring Boot 3.2 依赖管理最终解析 Jackson 2.15.3 与 Reactor 3.6.0，而 MCP 2.0.0 发布 POM 分别声明 2.20.1 与 3.7.0；专项协议测试已通过，但生产集成仍需关注此版本差异
- 为避免 SDK 直接记录未经脱敏的远端协议内容，`io.modelcontextprotocol` 日志级别设为 `off`；连接状态和标准错误仍由本地网关记录，排障时不应临时开启 SDK 原始日志并处理真实凭据
