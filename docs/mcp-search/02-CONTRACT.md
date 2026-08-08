# MCP 搜索接入：冻结契约

## 冻结规则

本文档是并行任务 T01、T02、T03、T04 的共同契约。专项任务如需改变命名、配置、Java 类型、返回格式或错误语义，必须停止修改并交由主协调任务记录决策。

## 依赖契约

- MCP Java SDK BOM：`io.modelcontextprotocol.sdk:mcp-bom:2.0.0`；
- JSON/Transport：`mcp-core` + `mcp-json-jackson2`；
- 不使用默认携带 Jackson 3 的 `mcp` 聚合依赖；
- 不升级项目现有 Spring AI `1.0.0-M4`；
- 使用 SDK 自带 JDK HttpClient Streamable HTTP Transport；
- 使用同步 MCP Client，与当前阻塞式 `AgentExecutionService` 保持一致。

依据：

- <https://java.sdk.modelcontextprotocol.io/latest/quickstart/>
- <https://java.sdk.modelcontextprotocol.io/latest/client/>
- <https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0>
- <https://modelcontextprotocol.io/specification/2025-11-25/basic/transports>

## Java 契约

包：`com.javaee.aiservice.agent.execution.mcp.contract`

### `McpToolDescriptor`

保存以下原始且供应商无关的信息：

- `serverId`：本地配置标识，例如 `web-search`；
- `remoteName`：Server 返回的原始工具名；
- `exposedName`：暴露给 Planner 的命名空间工具名；
- `description`：工具描述；
- `inputSchema`：完整原始 JSON Schema；
- `annotations`：原始 annotations，仅用于展示和审计，不作为可信授权依据。

### `McpToolGateway`

- `discoverTools()`：发现并返回 allowlist 后的工具；
- `supports(exposedToolName)`：检查是否可路由；
- `callTool(exposedToolName, arguments)`：执行工具。

### `McpToolCallResult`

连接层必须保留：

- Server ID、远程工具名、暴露工具名；
- `content` 原始内容块；
- `structuredContent`；
- 非敏感 metadata；
- 标准错误码与错误消息；
- 调用耗时。

## 配置契约

最终配置前缀固定为：

```yaml
agent:
  mcp:
    enabled: false
    fail-fast: false
    servers:
      web-search:
        enabled: true
        transport: streamable-http
        base-url: ${TAVILY_MCP_BASE_URL:https://mcp.tavily.com}
        endpoint: ${TAVILY_MCP_ENDPOINT:/mcp/}
        api-key: ${TAVILY_API_KEY:}
        namespace: mcp.web-search
        connect-timeout: 5s
        request-timeout: 20s
        max-result-chars: 30000
        allowed-tools:
          - tavily-search
```

约束：

- 默认关闭 MCP，保证兼容现有部署；
- `api-key` 为空时该 Server 视为不可用，不阻断服务启动；
- 不允许在 URL Query 中放置 API Key；
- 不记录完整 Authorization Header；
- 实际远程工具名必须来自 `tools/list`，配置只负责过滤。

## 工具命名契约

```text
<namespace>.<remote-tool-name>
```

首个工具预期为：

```text
mcp.web-search.tavily-search
```

规则：

- 原生工具名保持不变；
- MCP 工具不得覆盖已有名称；
- Server ID、远程名称和暴露名称必须同时进入审计元数据；
- 工具 category 固定为 `mcp-search`；
- `destructive=false`、`requiresUserAction=false`、`riskLevel=low` 由本地策略设置，不信任远程声明。

## Schema 契约

- 保留完整 `inputSchema`，不能只降级成 `Map<String, String>`；
- Planner 展示层可以生成简化参数说明；
- 校验至少支持 object、string、integer、number、boolean、array、enum、required、minimum、maximum、pattern；
- SDK 校验与本地校验不能改变远程参数含义；
- 缺少必填参数时复用现有 `action_required`/继续任务机制；
- allowlist 校验必须早于参数校验和远程调用。

## MCP 到 Agent 的结果契约

成功结果的 `AgentToolResult.data` 至少包含：

```json
{
  "query": "用户查询",
  "results": [
    {
      "title": "网页标题",
      "url": "https://example.com",
      "snippet": "摘要",
      "score": 0.91
    }
  ],
  "sources": ["https://example.com"],
  "mcp": {
    "serverId": "web-search",
    "remoteToolName": "tavily-search",
    "exposedToolName": "mcp.web-search.tavily-search",
    "durationMs": 800
  }
}
```

约束：

- `structuredContent` 优先，文本内容作为兼容回退；
- URL 去重但保持原始顺序；
- 空结果仍是成功调用，但最终回答要说明未找到结果；
- 不把完整原始响应无上限写入上下文；
- 最终回答必须仅引用实际 `sources` 中的 URL。

## 错误契约

| 错误码 | 语义 | 是否建议重试 |
| --- | --- | --- |
| `MCP_DISABLED` | MCP 或 Server 未启用 | 否 |
| `MCP_UNAVAILABLE` | 无法连接远程 Server | 是 |
| `MCP_AUTH_FAILED` | API Key 无效或缺失 | 否 |
| `MCP_TIMEOUT` | 初始化或调用超时 | 是 |
| `MCP_TOOL_NOT_ALLOWED` | 工具不在 allowlist | 否 |
| `MCP_TOOL_NOT_FOUND` | 远程工具不存在 | 刷新后最多一次 |
| `MCP_INVALID_ARGUMENTS` | 参数不符合 Schema | 补充参数后重试 |
| `MCP_TOOL_ERROR` | Server 返回 `isError=true` | 视消息决定 |
| `MCP_PROTOCOL_ERROR` | 协议或反序列化异常 | 否 |
| `MCP_RESULT_TOO_LARGE` | 结果超过本地限制 | 缩小结果后重试 |

## Planner 契约

- 最新、当前、近期、网页、外部资料、明确联网意图：允许选择 MCP Search；
- 上传材料、知识库、文档内容：优先内部 RAG；
- 用户禁止联网：禁止 MCP；
- MCP 不可用时不得声称获得实时结果；
- 规则兜底只处理明确的外部搜索意图，不替代模型对复杂任务的规划。

## HTTP API 兼容契约

- 不新增对外 Controller 作为 MVP 必需项；
- `/api/ai/agent/tools` 自动展示注册成功的 MCP 工具；
- `/api/ai/agent/execute` 继续作为执行入口；
- 现有响应字段保持兼容，只在工具结果和 Trace 中增加 MCP 元数据。
