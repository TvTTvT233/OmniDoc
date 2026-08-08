# T04 交接任务：独立验证与交付

## 开始前必读

1. `AGENTS.md`
2. `docs/OMNIDOC_CONTEXT.md`
3. `docs/requirements/SCHOLARSHIP_REVIEW_V1.md`
4. `docs/PROGRESS.md`
5. `docs/mcp-search/00-GOAL_AND_SCOPE.md`
6. `docs/mcp-search/01-ARCHITECTURE.md`
7. `docs/mcp-search/02-CONTRACT.md`
8. `docs/mcp-search/03-TASK_BOARD.md`
9. `docs/mcp-search/05-AUTO_TEST_REPORT.md`
10. `docs/mcp-search/06-HUMAN_ACCEPTANCE.md`

## 目标

从黑盒和集成角度独立验证 MCP 搜索接入，不替开发任务自行修复核心实现，并形成可复现的自动与人工验收证据。

## 允许修改

- 独立模拟 MCP Server/fixture；
- 新增 MCP 集成、回归、安全测试；
- 测试报告、README 和人工验收文档；
- 本文件和 `PROGRESS.md` 的 T04 区域。

## 禁止修改

- MCP Transport 核心实现；
- 工具注册表核心实现；
- Agent 执行和路由核心实现；
- 通过放宽断言掩盖缺陷；
- 把缺少真实 Key 写成真实远程验证通过。

## 必须覆盖

- MCP 关闭兼容；
- 初始化、`tools/list`、`tools/call`；
- allowlist 和命名空间；
- Schema 必填与类型；
- 成功、空结果、`isError=true`；
- 401、超时、断网、协议错误、过大结果；
- 内部 RAG/外部 Search/禁止联网路由；
- 最终来源引用；
- Trace MCP 元数据；
- API Key 泄漏检查；
- 原有测试回归。

## 缺陷交接

发现问题时记录：

- 最小复现步骤；
- 预期结果和实际结果；
- 日志或测试证据；
- 影响级别；
- 建议归属 T01、T02 或 T03；
- 不直接跨边界修复。

## 交付格式

- 状态：`HANDOFF_READY`、`AUTO_VERIFIED` 或 `BLOCKED`；
- 分支与提交 ID；
- 测试矩阵；
- 实际命令、通过数、失败数；
- 真实 Tavily 冒烟状态；
- 未解决缺陷；
- 人工验收入口和注意事项。
