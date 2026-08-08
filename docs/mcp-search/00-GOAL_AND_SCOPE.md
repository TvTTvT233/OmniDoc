# MCP 搜索接入：目标与范围

## 文档状态

- 任务编号：T00
- 状态：契约已冻结，共同基线已建立
- 日期：2026-08-09
- 首个目标服务：Tavily Remote MCP

## 背景

OmniDoc 当前已经具备自研的 Agent 规划、工具注册、执行、反思、审批和 Trace 链路，但工具来源仍以本地静态注册为主。此次改造让 OmniDoc 首次以 MCP Client 身份发现并调用一个外部只读搜索工具，同时保留现有内部 RAG 和原生工具链路。

## MVP 目标

用户提出需要外部或时效性信息的问题时，Agent 能够完成以下闭环：

1. 从受信任的远程 MCP Server 发现白名单搜索工具；
2. 将 MCP 工具元数据和 JSON Schema 映射到现有工具目录；
3. Planner 在内部 RAG、外部搜索和直接回答之间作出可解释选择；
4. 通过 MCP `tools/call` 执行搜索；
5. 将搜索结果规范化为现有 `AgentToolResult`；
6. 最终回答保留可访问的来源 URL；
7. Trace 记录 MCP Server、工具、状态、耗时和错误类别。

## MVP 范围

- OmniDoc 作为 MCP Client；
- Streamable HTTP 传输；
- 单个远程搜索 Server；
- 仅开放 allowlist 中的只读搜索工具；
- 启动时发现工具；
- 兼容 MCP 文本内容与 `structuredContent`；
- 参数校验、超时、错误归一化、密钥脱敏；
- MCP 不可用时 fail-open，不阻断 `ai-service` 启动；
- 模拟 Server 自动测试和一次可选的真实 Tavily 冒烟测试。

## 明确不在范围内

- 将 OmniDoc 暴露为 MCP Server；
- 多 MCP Server 管理后台；
- STDIO、旧 SSE 或本地进程托管；
- Tavily extract、crawl、map、research；
- MCP resources、prompts、sampling、elicitation；
- 写入、删除、命令执行类外部工具；
- 自动覆盖用户文档；
- 将外部网页内容直接写入知识库；
- 升级整个 Spring AI 依赖栈。

## 完成定义

只有同时满足以下条件，MCP 搜索接入才能标记为完成：

- 自动化测试报告记录实际命令和结果；
- MCP 开启与关闭模式均通过回归；
- 外部实时问题选择 MCP Search，内部文档问题保留 RAG；
- 搜索结果与最终答案保留来源；
- MCP 断网、超时、认证失败均有可理解错误；
- API Key 未出现在仓库、日志、Trace 或接口返回中；
- 真实远程调用若未执行，必须标记为未验证；
- 用户完成人工验收并明确确认。
