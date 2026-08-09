# MCP 搜索接入：人工验收

## 当前状态

- T04 独立验收资产：`HANDOFF_READY`；
- T01-T03：在 T04 共同基线中尚未集成；
- 自动验收：`PENDING_INTEGRATION`，不是 `AUTO_VERIFIED`；
- 真实 Tavily：当前进程无 `TAVILY_API_KEY`，未连接、未调用；
- 人工结论：`MANUAL_PENDING`。

在自动报告明确变为 `AUTO_VERIFIED` 前，不得勾选本文件的最终结论。

## 验收前提

- T01-T04 已全部集成到同一验收分支；
- 自动测试报告为 `AUTO_VERIFIED`；
- 本地以环境变量提供真实 `TAVILY_API_KEY`；
- 不在对话、截图、日志或提交中展示 Key；
- `ai-service` 已成功启动。
- 验收人已登录 OmniDoc；`/api/ai/agent/**` 虽可通过 Spring Security 路径过滤，但 Controller 仍要求已认证用户上下文；
- 已准备一个可查询的内部知识库和一份仅用于验收的材料。

## 验收入口

先在仓库根目录运行集成门禁：

```powershell
.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated
```

必须满足：命令退出码为 0，Maven 无失败/错误/跳过的 MCP 验收用例，静态泄漏检查通过。当前共同基线运行该命令会按设计退出 1 并报告 `PENDING_INTEGRATION`。

真实 Key 只在启动 `ai-service` 的本地进程环境中设置。不得把 Key 写在命令行参数、URL Query、YAML、验收记录或截图中；验收结束后清除该进程环境并关闭终端。

人工操作入口优先使用已登录的 Agent Workbench。若使用 HTTP 客户端，认证令牌也只从本地环境读取，不粘贴到本文档。

## 用例 1：工具发现

1. 打开 `GET /api/ai/agent/tools`；
2. 查找 `mcp.web-search.tavily-search`；
3. 确认 category 为 `mcp-search`、风险为 low、危险操作为 false；
4. 确认 extract、crawl、map、research 未出现。

通过标准：只出现 allowlist 中的搜索工具，且参数 Schema 中 `query` 为 string/必填；integer、number、boolean、array、enum、minimum、maximum、pattern 均未降级成字符串。

证据记录：保存脱敏后的响应字段名与断言结果，不保存 Authorization Header。

## 用例 2：外部搜索

任务示例：

```text
搜索截至今天最新的 Agent 技术趋势，给出结论和来源链接。
```

检查：

- Plan 中选择 MCP 搜索工具；
- `toolResults` 状态为 success；
- 至少返回一个网页 URL；
- `toolResults.data.mcp` 包含 `serverId=web-search`、远程工具名、暴露工具名和非负耗时；
- 最终答案引用的 URL 来自 `sources`；
- Timeline 显示 Server、工具名、状态和耗时；
- 回答内容与访问到的页面时间相符，且没有把网页中的指令性文本当作系统指令。

通过标准：真实 Tavily 至少成功返回一个结果，最终引用集合是 `sources` 的子集；若真实查询无结果，应更换一个稳定查询重试一次，不能把空结果记作连通成功。

## 用例 3：内部 RAG 不回归

任务示例：

```text
只根据我上传的申请材料总结目前缺少哪些证明。
```

通过标准：使用内部 RAG/文档工具，不调用外部 MCP。

附加检查：`plan`、`toolResults`、Timeline 和服务端脱敏调用计数中均没有 `mcp.` 工具。

## 用例 4：明确禁止联网

任务示例：

```text
不要联网，只根据当前上下文回答。
```

通过标准：计划中没有 MCP 工具。

附加检查：即使任务中包含“最新”“网页”等词，显式禁止联网仍优先；最终回答不得声称已检索实时资料。

## 用例 5：MCP 关闭

关闭 `agent.mcp.enabled` 后重启服务。

通过标准：

- 服务正常启动；
- MCP 工具不出现在工具列表；
- 原生 Agent 工具仍可使用；
- 重跑原有 Agent/Controller 回归无失败。

## 用例 6：认证失败

使用无效的临时 Key 启动或调用。

通过标准：

- 返回认证失败而不是未知异常；
- 不重复重试认证失败；
- 错误码为 `MCP_AUTH_FAILED`；
- 日志、Trace、HTTP 返回中没有 Key；
- 错误信息不包含完整 Authorization Header、URL Query Key 或无关远端响应体。

## 用例 7：网络失败

在安全测试环境中使用不可达 MCP 地址。

通过标准：

- `ai-service` 仍能启动；
- MCP 工具不可用状态明确；
- 调用超时有边界，不无限等待；
- 不可达映射为 `MCP_UNAVAILABLE`，慢响应映射为 `MCP_TIMEOUT`；
- 原生工具不受影响。

## 用例 8：结果边界与错误语义

在集成自动测试中使用 T04 fixture 依次执行：

- `fixture:text-only`；
- `fixture:empty`；
- `fixture:error`；
- `fixture:protocol-error`；
- `fixture:oversized`。

通过标准：分别验证文本回退、空结果成功提示、`MCP_TOOL_ERROR`、`MCP_PROTOCOL_ERROR`、`MCP_RESULT_TOO_LARGE`；超限内容不得进入模型上下文，错误不得伪装成实时搜索成功。

## 用例 9：密钥与敏感信息复核

1. 重跑 `.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated`；
2. 检查本次验收产生的 `ai-service` 日志、Agent Trace、`toolResults` 和 HTTP 错误返回；
3. 检查 Tavily 请求 URL 不含 Key Query；
4. 检查日志中没有完整 Bearer Header；
5. 只记录“通过/失败、证据位置、时间”，不要复制疑似密钥文本。

通过标准：仓库静态扫描、日志、Trace、工具结果和 HTTP 返回均无真实 Key；若发现疑似泄漏，立即停止验收、撤销 Key，并把缺陷归属 T01/T03，不在 T04 修改核心实现。

## 缺陷记录模板

```text
用例：
影响级别：
最小复现：
预期：
实际：
脱敏证据位置：
归属：T01 / T02 / T03
```

禁止把真实 Key、Authorization Header 或含凭据 URL 写入缺陷记录。

## 人工验收结论

- [ ] 工具发现通过
- [ ] 外部搜索通过
- [ ] 内部 RAG 路由通过
- [ ] 禁止联网通过
- [ ] MCP 关闭兼容通过
- [ ] 认证失败处理通过
- [ ] 网络失败处理通过
- [ ] 结果边界与错误语义通过
- [ ] 密钥检查通过

验收人：待填写

验收日期：待填写

最终结论：`MANUAL_PENDING`
