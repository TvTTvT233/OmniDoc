# T02 交接任务：动态工具目录与 Schema

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

将 `McpToolDescriptor` 安全映射为 Planner 可见的 `AgentToolDefinition`，并让 `AgentToolRegistry` 支持命名空间外部工具的动态注册、刷新和移除。

## 允许修改

- `agent/execution/tool`；
- MCP Schema/registry adapter；
- T02 独立测试；
- 本文件和 `PROGRESS.md` 的 T02 区域。

## 禁止修改

- 已冻结的 `mcp.contract`，除非先请求主协调任务变更；
- Maven MCP SDK 依赖；
- HTTP Client/Transport；
- `AgentExecutionService`；
- Planner 和最终回答逻辑。

## 必须实现

- `mcp.web-search.<remote-name>` 命名空间；
- 本地 allowlist 二次校验；
- 原始 JSON Schema 保留；
- Planner 简化参数说明；
- object/string/integer/number/boolean/array/enum/required/min/max/pattern 映射；
- 外部工具来源元数据；
- 本地固定低风险、只读策略；
- 原子刷新，失败保留上一版；
- 工具名冲突保护；
- 原生工具行为不变。

## 自验收

- 注册后 `/api/ai/agent/tools` 可见；
- query 必填；
- 数组和数字类型不降级成字符串；
- 白名单外工具不可注册；
- 外部工具不能覆盖原生工具；
- 刷新不会产生重复工具；
- 移除指定 Server 工具不影响其他工具；
- 现有工具目录相关测试通过。

## 交付格式

- 状态：`HANDOFF_READY` 或 `BLOCKED`；
- 分支与提交 ID；
- 修改文件；
- 实际测试命令和结果；
- 未验证项；
- Schema 支持边界和剩余风险。
