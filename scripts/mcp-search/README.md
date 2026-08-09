# T04 MCP 搜索验收入口

本目录只包含 T04 测试运行入口和静态密钥泄漏检查，不修改 MCP Transport、工具注册表或 Agent 路由实现。

## 当前共同基线

在仓库根目录执行：

```powershell
.\scripts\mcp-search\run-t04-acceptance.cmd
```

该命令运行：

- 独立 `MockMcpServer` fixture 的 9 个自检；
- `McpContractTest`；
- `AgentExecutionServiceTest` 与 `AgentControllerTest` 回归；
- Git 可见源码/文档中的 Tavily Key、URL 凭据、Bearer 字面量和私钥标记扫描。

成功仅表示上述当前可运行范围通过。命令会明确输出 `PENDING_INTEGRATION`，不得据此标记 `AUTO_VERIFIED`。

## T01-T03 集成后

```powershell
.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated
```

集成模式要求生产源码中已存在非契约 MCP 实现，并运行全部 `*Mcp*Test` 加既有 Agent/Controller 回归。若实现尚未集成，脚本直接失败，不把缺失用例当作通过。

`MockMcpServer` 位于：

```text
ai-service/src/test/java/com/javaee/aiservice/agent/execution/mcp/acceptance/MockMcpServer.java
```

Java 集成测试可通过 `MockMcpServer.start("fixture token")` 获取随机本地端口的 `/mcp/` 地址。fixture 仅绑定 loopback，要求 Bearer、会话头和协议版本头，并提供以下确定性查询：

| query | 场景 |
| --- | --- |
| `fixture:success` | structured content + 文本兼容内容、重复 URL、外部不可信文本 |
| `fixture:text-only` | 仅文本兼容回退 |
| `fixture:empty` | 空结果成功 |
| `fixture:error` | `isError=true` |
| `fixture:slow` | 有界慢响应，用于客户端超时 |
| `fixture:protocol-error` | HTTP 200 + 非法 JSON |
| `fixture:oversized` | 超过冻结的 30000 字符限制 |

Server 的 `tools/list` 分两页返回 `tavily-search` 以及三个白名单外工具，并故意把 Search annotations 声明为 destructive，用于验证客户端必须执行本地 allowlist 与低风险只读策略。

## 检查边界

静态脚本不会访问环境变量值，也不会输出疑似密钥内容。它只报告文件、行号和规则名；Git 历史、运行日志、Trace 和 HTTP 返回仍需在集成环境按人工验收文档复核。
