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

## T02 实施交接

### 状态

- 状态：`HANDOFF_READY`
- 分支：`feature/mcp-search-tool-catalog`
- 基线：`61266db`
- 提交 ID：以本分支 T02 交付提交及最终交接消息为准

### 已完成

- 新增 `McpToolDefinitionMapper`，仅将本地 allowlist 中、且 `serverId`、namespace 与 exposed name 一致的描述映射为工具定义；默认 namespace 为 `mcp.<serverId>`，首个 Server 对应 `mcp.web-search.<remote-name>`。
- `AgentToolDefinition` 新增完整只读 `inputSchema`、来源元数据和显式 `readOnly`；MCP 来源元数据保留 Server ID、远程名称、暴露名称、namespace 与原始 annotations。
- 将 object、string、integer、number、boolean、array、enum、required、minimum、maximum、pattern、default 和 description 映射到 Planner 可见的简化参数说明及 `parameterSchema`，数组与数字类型不降级为字符串。
- MCP Search 的 category、只读、非破坏、无需用户动作和低风险属性完全由本地映射固定；远程 annotations 只保留用于展示/审计，不参与授权。
- `AgentToolRegistry` 新增按 Server 原子替换、列出和移除 MCP 工具的能力；候选集完整校验后才替换，重复名称、原生工具冲突、其他 Server 冲突或策略不符都会拒绝刷新并保留旧版本。
- 原生工具构造器、名称、参数、顺序与查询方式保持兼容；移除一个 Server 时不会影响原生工具或其他 Server 工具。

### 修改文件

- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/tool/AgentToolDefinition.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/tool/AgentToolParameterDefinition.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/tool/AgentToolRegistry.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/tool/McpToolDefinitionMapper.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/tool/McpToolCatalogAdapter.java`
- `ai-service/src/test/java/com/javaee/aiservice/agent/execution/tool/McpToolCatalogAdapterTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/controller/McpToolCatalogEndpointTest.java`
- `docs/mcp-search/handoffs/T02-tool-catalog.md`
- `docs/PROGRESS.md`（仅 T02 区域）

### 验证结果

- `.\mvnw.cmd -pl ai-service -am "-Dtest=McpToolCatalogAdapterTest,McpToolCatalogEndpointTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：8/8 通过。
- `.\mvnw.cmd -pl ai-service -am "-Dtest=AgentExecutionServiceTest,AgentControllerTest,McpContractTest,McpToolCatalogAdapterTest,McpToolCatalogEndpointTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：43/43 通过。
- 覆盖：`/api/ai/agent/tools` 可见、query required、数组/数字类型、allowlist 二次过滤、本地策略覆盖远程 annotations、原生冲突、重复刷新、失败保留旧版、重复候选拒绝、按 Server 隔离移除及现有工具目录回归。

### Schema 支持边界

- Planner 简化映射支持根 object Schema 的直接 properties；property 类型支持 object/string/integer/number/boolean/array。
- 映射 enum、required、minimum、maximum、pattern、default、description；完整原始 Schema（含嵌套 properties/items 和未简化关键字）保留在 `inputSchema`。
- array 的直接类型和一级 items type 会进入 Planner 说明；嵌套对象/数组的详细约束不扁平化，调用阶段可从完整 `inputSchema` 读取。
- property 缺失/使用不支持的 type，或使用 union type、`oneOf`、`anyOf`、`allOf`、`$ref` 代替直接 type 时，当前简化映射会拒绝该刷新并保留上一版。
- `minLength`、`maxLength`、`minItems`、`maxItems`、`exclusiveMinimum`、`exclusiveMaximum`、`additionalProperties` 等关键字仅原样保留，尚未映射到现有 `AgentToolParameterDefinition` 的简化字段。

### 未验证项与风险

- 未引入或运行真实 MCP SDK/HTTP Transport；未执行真实 Tavily MCP、完整服务启动或 T01-T03 端到端联调。
- 当前只完成目录与 Schema 映射；远程调用路由、完整 JSON Schema 运行时校验和最终结果合成属于 T03/集成任务。
- 刷新原子性由注册表同步临界区及“先构造候选、后替换”保证，已验证失败回滚语义；尚未执行高并发压力测试。
