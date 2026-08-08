# MCP 搜索接入：任务看板

## 状态定义

- `TODO`：尚未开始；
- `IN_PROGRESS`：正在进行；
- `BLOCKED`：存在明确阻塞；
- `HANDOFF_READY`：专项任务已自测并交接；
- `MERGED`：主协调任务已集成；
- `AUTO_VERIFIED`：自动验收通过；
- `HUMAN_ACCEPTED`：用户人工验收通过。

## 当前看板

| 编号 | 任务 | 建议分支 | 状态 | 依赖 |
| --- | --- | --- | --- | --- |
| T00 | 基线、契约与任务拆分 | `docs/bootstrap-project-context` | `HANDOFF_READY` | 无 |
| T01 | MCP 连接与协议层 | `feature/mcp-search-transport` | `IN_PROGRESS` | T00 基线提交 |
| T02 | 动态工具目录与 Schema | `feature/mcp-search-tool-catalog` | `IN_PROGRESS` | T00 基线提交 |
| T03 | Agent 路由、调用与结果合成 | `feature/mcp-search-agent-routing` | `IN_PROGRESS` | T00 基线提交 |
| T04 | 独立自动验收与人工验收文档 | `test/mcp-search-acceptance` | `IN_PROGRESS` | T00；最终验证依赖 T01-T03 集成 |

## Codex 任务与工作树

| 编号 | Codex 任务 ID | 工作树 |
| --- | --- | --- |
| T01 | `019fe237-e23b-7c60-a394-658c32ea49d5` | `../.codex-worktrees/mcp-search-t01` |
| T02 | `019fe238-0377-7b12-aeef-63bb25114509` | `../.codex-worktrees/mcp-search-t02` |
| T03 | `019fe238-24bf-7f50-a220-fb0267461265` | `../.codex-worktrees/mcp-search-t03` |
| T04 | `019fe238-3e05-7540-aa00-f66dc4cfcdaf` | `../.codex-worktrees/mcp-search-t04` |

## T01 文件所有权

允许：

- 根 `pom.xml` 中 MCP BOM 版本；
- `ai-service/pom.xml` MCP 依赖；
- MCP properties、client、transport、lifecycle 实现；
- MCP 连接层测试；
- T01 交接和进展区域。

禁止：

- `AgentToolRegistry`；
- `AgentExecutionService`；
- Planner 和结果合成逻辑。

## T02 文件所有权

允许：

- `agent/execution/tool` 下工具定义和注册表；
- MCP Schema/registry adapter；
- 对应工具目录测试；
- T02 交接和进展区域。

禁止：

- SDK Transport；
- `AgentExecutionService`；
- 最终回答逻辑。

## T03 文件所有权

允许：

- `AgentExecutionService`；
- 必要的 `AgentReflectionService` 调整；
- MCP 到 `AgentToolResult` 的适配；
- Agent 路由和引用测试；
- T03 交接和进展区域。

禁止：

- Maven MCP 依赖；
- HTTP Transport；
- 工具注册表结构性重构。

## T04 文件所有权

允许：

- 独立 MCP 模拟 Server/fixture；
- MCP 集成测试和回归测试；
- 测试报告、README、人工验收文档；
- T04 交接和进展区域。

发现实现缺陷时不得顺手修改核心实现，应把复现步骤交回对应任务。

## 集成顺序

1. T02 工具目录；
2. T01 连接层；
3. T03 Agent 集成；
4. T04 验收资产；
5. 主协调任务执行完整自动验收；
6. 用户人工验收。
