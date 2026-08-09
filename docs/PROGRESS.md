# OmniDoc 项目进展

## 状态快照

- **更新日期**：2026-08-09
- **当前阶段**：MCP 搜索接入 T01-T04 独立任务并行开发
- **首个专业场景**：奖学金申报材料审查 V1
- **下一阶段**：收集 T01-T04 交接提交，按 T02、T01、T03、T04 顺序集成验证

## 已确认

以下内容为已经确认的产品或需求结论，不等同于功能运行验证：

- OmniDoc 定位为“通用文档智能工作台 + 多场景文档审查 Agent 平台”。
- 保留文件管理、文档解析、编辑、版本、RAG、通用 Agent、工具调用、审批、Trace 和 PPT 等能力方向。
- 首个专业场景及 V1 目标用户已经确定。
- V1 输入材料、七类输出、证据规则和人工确认原则已经冻结。
- V1 不做获奖概率预测、印章真伪识别、自动提交和自动覆盖原始文件。
- 项目上下文、V1 需求和仓库协作约定已经形成文档基线。
- MCP 搜索接入的 MVP 范围、架构、Java 契约、错误语义、任务边界和验收标准已经冻结。
- `AgentExecutionServiceTest` 与 `AgentControllerTest` 在当前工作树上共 32 个测试通过，可作为 MCP Agent 集成的直接回归基线；加入 T00 契约测试后相关测试为 35/35 通过。

## 尚未验证

运行基线验证仍未完整完成，因此不得将以下事项描述为已验证：

- 各服务在目标环境中的启动、依赖和配置状态；
- 文件、解析、编辑、版本、RAG、Agent、工具调用、审批、Trace 和 PPT 能力的实际可用性；
- `AgentExecutionService`、`AgentToolRegistry`、RAG 与文档能力对奖学金审查场景的接口契合度；
- 从材料输入到审查结果、证据追溯和人工确认的端到端链路；
- 奖学金审查 V1 的实现完成度、测试覆盖、性能与生产可用性。
- 真实模型、Tavily MCP、完整服务启动和端到端外部搜索链路的可用性。

## 后续运行基线验证

建议按以下顺序推进并记录实际结果：

1. 确认本地运行所需的基础设施、配置和依赖；
2. 记录各服务的启动命令、成功条件与实际结果；
3. 逐项盘点需保留的通用能力及其入口、接口和验证状态；
4. 验证 `AgentExecutionService`、`AgentToolRegistry`、RAG 和文档能力的最小复用路径；
5. 基于验证结果识别奖学金审查 V1 的最小实现缺口与风险；
6. 在进入实现前更新本文件，不把静态代码存在等同于运行可用。

## MCP 搜索接入 T00

### 已完成

- 已完整读取仓库协作约定、项目上下文、奖学金审查 V1 需求和本进展文档；
- 已修复 Git `safe.directory` 阻塞，仅添加仓库精确路径；
- 已记录 T00 起始基线：分支 `docs/bootstrap-project-context`，HEAD `8427f59`，暂存区为空；
- 已盘点进入 T00 前的工作树：18 个已跟踪修改、16 个未跟踪文件；
- 已按用户选择的方案 A 审查原有改动与敏感信息，并形成独立 checkpoint 提交 `51caddf`；
- 已建立 `docs/mcp-search/` 目标、架构、契约、任务、集成、自动测试和人工验收文档；
- 已新增不依赖具体 MCP SDK 的 `mcp.contract` Java 编译边界；
- 已冻结使用 MCP Java SDK 2.0.0、Jackson 2、Sync Client 和 Streamable HTTP 的方案；
- 已明确不升级现有 Spring AI，不启用非工具 MCP 能力，MVP 只暴露白名单 Search。

### 验证结果

- 默认完整测试：53 个测试中 52 个通过、1 个错误；错误原因是缺少 `DASHSCOPE_API_KEY` 导致 `AllModelsTest` 上下文加载失败；
- 测试进程使用非真实占位配置后：53/53 通过；其中四个真实模型调用均认证失败，实际模型连通为 0/4，该结果不能证明模型服务可用；
- MCP 直接相关回归：`AgentExecutionServiceTest` + `AgentControllerTest` 共 32/32 通过；
- T00 契约加入后的直接回归：上述测试加 `McpContractTest` 共 35/35 通过；
- 端到端服务启动：未执行；
- 真实 Tavily MCP：未执行。

### 基线决策

用户已选择方案 A。进入 T00 前的 34 个文件已经审查并保存到 checkpoint `51caddf`；T00 契约和文档作为后续独立提交，不再存在工作树保存策略阻塞。T01-T04 已于 2026-08-09 从共同基线 `61266db` 创建独立分支、工作树和 Codex 任务并开始执行。

## MCP 搜索并行任务进展

### T00：基线、契约与任务拆分

- 状态：`HANDOFF_READY`
- 分支：`docs/bootstrap-project-context`
- 验证：直接相关测试 35/35 通过；真实 Tavily MCP 未验证

### T01：MCP 连接与协议层

- 状态：`IN_PROGRESS`
- 分支：`feature/mcp-search-transport`
- Codex 任务：`019fe237-e23b-7c60-a394-658c32ea49d5`
- 工作树：`../.codex-worktrees/mcp-search-t01`
- 验证：任务执行中，结果待交接

### T02：动态工具目录与 Schema

- 状态：`MERGED`
- 分支：`feature/mcp-search-tool-catalog`
- 提交：`9128188`
- Codex 任务：`019fe238-0377-7b12-aeef-63bb25114509`
- 工作树：`../.codex-worktrees/mcp-search-t02`
- 实现：已完成 MCP allowlist 二次过滤、`mcp.<serverId>.<remote-name>` 命名、完整原始 Schema 与来源元数据保留、支持类型/required/enum/数值范围/pattern 的 Planner 映射、本地固定只读低风险策略，以及按 Server 原子刷新、失败保留旧版、冲突保护和隔离移除；原生工具行为保持兼容。
- 验证：T02 新增测试 8/8 通过；加入 `AgentExecutionServiceTest`、`AgentControllerTest` 和 `McpContractTest` 的直接回归共 43/43 通过。
- 未验证：真实 MCP SDK/Transport、Tavily 远程调用、完整服务启动、T01-T03 联调和高并发刷新压力；union/组合 Schema 与部分高级约束仅保留原始 Schema，未进入简化参数字段。
- 下一步：主协调任务按 T02 -> T01 -> T03 顺序集成，并由 T04 执行模拟 Server 与完整自动验收。

### T03：Agent 路由、调用与结果合成

- 状态：`IN_PROGRESS`
- 分支：`feature/mcp-search-agent-routing`
- Codex 任务：`019fe238-24bf-7f50-a220-fb0267461265`
- 工作树：`../.codex-worktrees/mcp-search-t03`
- 验证：任务执行中，结果待交接

### T04：独立验证与交付

- 状态：`IN_PROGRESS`
- 分支：`test/mcp-search-acceptance`
- Codex 任务：`019fe238-3e05-7540-aa00-f66dc4cfcdaf`
- 工作树：`../.codex-worktrees/mcp-search-t04`
- 验证：验收资产建设中；最终集成验证依赖 T01-T03

## 维护规则

每次任务完成后更新本文件，至少记录：

- 本次形成的真实进展；
- 实际执行的测试或检查及结果；
- 尚未验证的事项和剩余风险；
- 下一步动作。

若没有运行或测试，应明确写“未运行”或“未验证”，不得推测结果。

## 本次文档基线验证

- `git diff --cached --name-status`：暂存区仅包含本次要求新增的四个文档。
- `git diff --cached --check`：通过，未发现空白错误。
- 业务测试：未运行；本次仅新增文档，不修改业务代码，运行基线仍待下一阶段验证。

## 变更记录

| 日期 | 阶段 | 记录 |
| --- | --- | --- |
| 2026-08-09 | MCP 搜索 T01-T04 | 从共同基线 `61266db` 创建 4 个独立分支、Git 工作树和 Codex 任务，分别开始连接层、工具目录、Agent 路由与独立验收开发 |
| 2026-08-09 | MCP 搜索 T00 | 方案 A 已执行：原有 34 个文件审查后形成 checkpoint `51caddf`；T00 契约、测试基线和任务拆分已完成，真实 MCP 尚未接入 |
| 2026-07-26 | 需求冻结 | 初始化项目上下文、奖学金审查 V1 需求、协作约定与进展文档；未进行运行基线验证 |
