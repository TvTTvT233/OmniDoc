# MCP 搜索接入：人工验收

## 验收前提

- T01-T04 已全部标记为 `MERGED`；
- 自动测试报告为 `AUTO_VERIFIED`；
- 本地以环境变量提供真实 `TAVILY_API_KEY`；
- 不在对话、截图、日志或提交中展示 Key；
- `ai-service` 已成功启动。

## 用例 1：工具发现

1. 打开 `GET /api/ai/agent/tools`；
2. 查找 `mcp.web-search.tavily-search`；
3. 确认 category 为 `mcp-search`、风险为 low、危险操作为 false；
4. 确认 extract、crawl、map、research 未出现。

通过标准：只出现 allowlist 中的搜索工具，且参数 Schema 中 query 为必填。

## 用例 2：外部搜索

任务示例：

```text
搜索截至今天最新的 Agent 技术趋势，给出结论和来源链接。
```

检查：

- Plan 中选择 MCP 搜索工具；
- `toolResults` 状态为 success；
- 至少返回一个网页 URL；
- 最终答案引用的 URL 来自 `sources`；
- Timeline 显示 Server、工具名、状态和耗时。

## 用例 3：内部 RAG 不回归

任务示例：

```text
只根据我上传的申请材料总结目前缺少哪些证明。
```

通过标准：使用内部 RAG/文档工具，不调用外部 MCP。

## 用例 4：明确禁止联网

任务示例：

```text
不要联网，只根据当前上下文回答。
```

通过标准：计划中没有 MCP 工具。

## 用例 5：MCP 关闭

关闭 `agent.mcp.enabled` 后重启服务。

通过标准：

- 服务正常启动；
- MCP 工具不出现在工具列表；
- 原生 Agent 工具仍可使用。

## 用例 6：认证失败

使用无效的临时 Key 启动或调用。

通过标准：

- 返回认证失败而不是未知异常；
- 不重复重试认证失败；
- 日志、Trace、HTTP 返回中没有 Key。

## 用例 7：网络失败

在安全测试环境中使用不可达 MCP 地址。

通过标准：

- `ai-service` 仍能启动；
- MCP 工具不可用状态明确；
- 调用超时有边界，不无限等待；
- 原生工具不受影响。

## 人工验收结论

- [ ] 工具发现通过
- [ ] 外部搜索通过
- [ ] 内部 RAG 路由通过
- [ ] 禁止联网通过
- [ ] MCP 关闭兼容通过
- [ ] 认证失败处理通过
- [ ] 网络失败处理通过
- [ ] 密钥检查通过

验收人：待填写

验收日期：待填写

最终结论：`MANUAL_PENDING`
