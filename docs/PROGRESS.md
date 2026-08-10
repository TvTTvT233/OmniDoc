# OmniDoc 项目进展

## 状态快照

- **更新日期**：2026-08-10
- **当前阶段**：Qdrant QV01-QV04 已集成并通过自动验收；Qdrant 与 MCP 搜索均等待用户人工验收
- **首个专业场景**：奖学金申报材料审查 V1
- **下一阶段**：按 `docs/qdrant/06-HUMAN_ACCEPTANCE.md` 执行 Qdrant 人工验收；MCP 人工验收仍按 `docs/mcp-search/06-HUMAN_ACCEPTANCE.md` 执行

## 已确认

以下内容为已经确认的产品或需求结论，不等同于功能运行验证：

- OmniDoc 定位为“通用文档智能工作台 + 多场景文档审查 Agent 平台”。
- 保留文件管理、文档解析、编辑、版本、RAG、通用 Agent、工具调用、审批、Trace 和 PPT 等能力方向。
- 首个专业场景及 V1 目标用户已经确定。
- V1 输入材料、七类输出、证据规则和人工确认原则已经冻结。
- V1 不做获奖概率预测、印章真伪识别、自动提交和自动覆盖原始文件。
- 项目上下文、V1 需求和仓库协作约定已经形成文档基线。
- MCP 搜索接入的 MVP 范围、架构、Java 契约、错误语义、任务边界和验收标准已经冻结。
- Qdrant 接入采用保留 Redis + JVM HNSW 基线、增加 `baseline`/`shadow`/`qdrant` 三种模式的方案；公共 Java 契约、配置名称、任务边界和验收规则已经冻结。
- QV01-QV04 已按冻结顺序集成；生产装配、双后端、Shadow、可恢复迁移、scope 过滤和回滚边界已经进入主分支，专用自动验收为 159/159。
- `AgentExecutionServiceTest` 与 `AgentControllerTest` 在当前工作树上共 32 个测试通过，可作为 MCP Agent 集成的直接回归基线；加入 T00 契约测试后相关测试为 35/35 通过。

## 尚未验证

运行基线验证仍未完整完成，因此不得将以下事项描述为已验证：

- 各服务在目标环境中的启动、依赖和配置状态；
- 文件、解析、编辑、版本、RAG、Agent、工具调用、审批、Trace 和 PPT 能力的实际可用性；
- `AgentExecutionService`、`AgentToolRegistry`、RAG 与文档能力对奖学金审查场景的接口契合度；
- 从材料输入到审查结果、证据追溯和人工确认的端到端链路；
- 奖学金审查 V1 的实现完成度、测试覆盖、性能与生产可用性。
- 真实模型、Tavily MCP、完整服务启动和端到端外部搜索链路的可用性。
- Qdrant 用户人工验收、真实远程 Embedding/模型驱动的 RAG 闭环，以及生产 TLS、鉴权、容量、高可用、备份和灾备。

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

- 状态：`MERGED`
- 分支：`feature/mcp-search-transport`
- 提交：`04da0a4`
- Codex 任务：`019fe237-e23b-7c60-a394-658c32ea49d5`
- 工作树：`../.codex-worktrees/mcp-search-t01`
- 实现：已接入 MCP Java SDK 2.0.0 的 `mcp-core` 与 `mcp-json-jackson2`，使用 Sync Client 和 JDK HttpClient Streamable HTTP；已实现 Bearer Header、初始化、显式分页 `tools/list`、allowlist、`tools/call`、连接/请求超时、fail-open、优雅关闭、标准错误映射、结果大小限制和密钥脱敏；默认关闭时不创建远程 Client
- 验证：T01 专项测试 15/15 通过；加入 `AgentExecutionServiceTest`、`AgentControllerTest` 与 `McpContractTest` 后直接相关回归 50/50 通过；MCP 2.0.0 依赖与主代码编译通过
- 未验证：无真实 Tavily Key，未执行真实 Tavily MCP、完整 `ai-service` 外部基础设施启动或 Agent 端到端搜索；这些内容留待集成与验收任务
- 风险：Spring Boot 3.2 依赖管理最终解析 Jackson 2.15.3、Reactor 3.6.0，而 MCP 2.0.0 发布 POM 声明 Jackson 2.20.1、Reactor 3.7.0；当前专项协议测试通过，未升级现有 Spring AI/Boot 依赖栈，仍需在后续完整集成中关注版本兼容

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

- 状态：`MERGED`
- 分支：`feature/mcp-search-agent-routing`
- 提交：`4e0f3aa`
- Codex 任务：`019fe238-24bf-7f50-a220-fb0267461265`
- 工作树：`../.codex-worktrees/mcp-search-t03`
- 进展：已完成 MCP/RAG/禁网路由约束、`McpToolGateway` 分发、action-required 复用、structured/text 结果归一化、上下文大小限制、实际来源引用、MCP Trace 与失败语义；原生工具分发保持不变
- 验证：T03 新增测试 10/10；`AgentExecutionServiceTest` + `AgentControllerTest` 原有基线 32/32；加冻结契约测试后直接相关组合回归 45/45
- 未验证：T01/T02 合并联调、完整服务启动、真实模型、真实 Tavily MCP 与端到端远程搜索
- 下一步：由主协调任务按 T02、T01、T03 顺序集成，并由 T04 执行独立验收

### T04：独立验证与交付

- 状态：`AUTO_VERIFIED`
- 分支：`test/mcp-search-acceptance`
- 提交：`74bcdd6`
- Codex 任务：`019fe238-3e05-7540-aa00-f66dc4cfcdaf`
- 工作树：`../.codex-worktrees/mcp-search-t04`
- 基线：`61266dbbe268958c1e4d7ccbea9ccc5f15220ad8`
- 已完成：独立 Streamable HTTP 模拟 MCP Server、9 个 fixture 自检、统一验收入口、集成门禁、静态密钥泄漏检查和人工验收入口
- 独立分支验证：44/44 通过（fixture 9、MCP 契约 3、Agent/Controller 回归 32）；非真实占位配置下全量 65/65 通过但远程模型成功 0/4；静态检查 570 个 Git 可见文本文件无命中
- 独立分支门禁：`-Integrated` 在当时 T01-T03 未集成时按设计退出 1 并报告 `PENDING_INTEGRATION`，未把缺失实现计作通过
- 真实 Tavily：当前进程无 `TAVILY_API_KEY`，未连接、未调用、未验证
- 最终集成：T02、T01、T03、T04 已按顺序合并；集成连接器提交为 `ff9b619`
- 最终验证：T04 集成模式 78/78 通过，敏感信息静态检查扫描 588 个文本文件无命中；非真实占位配置下完整 `ai-service` 回归 99/99 通过，四个远程模型实际成功仍为 0/4

## MCP 搜索最终自动验收

- T01 合并提交：`01240a6`；
- T02 合并提交：`20e6e8d`；
- T03 合并提交：`bcfebe3`；
- T04 合并提交：`4670d2c`；
- 集成修复提交：`ff9b619`，负责把 T01 启动发现结果发布到 T02 动态工具目录；
- 模拟 Server 跨层链路：真实 SDK Transport → 分页发现 → allowlist → Schema 映射 → 动态注册 → `tools/call` 通过；
- T04 `-Integrated`：78/78 通过，Failures 0、Errors 0、Skipped 0；
- 完整 `ai-service`：99/99 通过，Failures 0、Errors 0、Skipped 0；
- 静态泄漏检查：588 个 Git 可见文本文件无命中；
- 真实 Tavily、真实模型、完整服务外部基础设施启动和用户人工路由验收仍未执行。

## Qdrant 向量后端 QV00（历史基线）

### 已完成

- 已建立 `docs/qdrant/` 目标、架构、冻结契约、任务看板、集成日志、自动测试和人工验收文档；
- 已新增供应商无关的 `rag.vector.contract` Java 边界，冻结后端、模式、租户作用域、Embedding 规格、迁移分页和错误语义；
- 已在 `application.yml` 与 `application-local.yml` 增加默认 `baseline` 的 Qdrant 配置骨架，当前运行路径尚未使用；
- 已新增确定性契约测试和现有 Redis + JVM HNSW 特征测试；
- 已冻结 QV01-QV04 的职责、文件所有权、集成顺序和验收门禁。

### 基线发现

- 相同逻辑 ID 覆盖写入时，当前 JVM HNSW 可能仍返回旧向量，后续删除也可能残留旧版本命中；
- QV00 未越界修改生产实现，两项回归测试已明确禁用并分配给 QV01 修复、启用；
- 该缺陷修复前，不得把现有 baseline 的幂等 upsert 和删除语义描述为已验证。

### 尚未验证

- QV01-QV04 已创建并进入并行开发，但尚未形成交接提交或集成；
- 现有 `VectorStore` 尚未接入新契约；
- Qdrant SDK、Docker、Collection、alias、payload index、Shadow 和迁移均未实现；
- 未连接真实 Redis、Qdrant 或 Embedding 服务；
- 未完成 Qdrant 自动集成验收或用户人工验收。

### QV00 验证结果

- QV00 专项：10 个测试，8 个通过、0 个失败、0 个错误、2 个按已知缺陷跳过；
- 完整 `ai-service`：109 个测试，107 个通过、0 个失败、0 个错误、2 个按已知缺陷跳过，`BUILD SUCCESS`；
- 完整回归使用非真实占位配置，远程模型实际成功仍为 0/4；
- QV00 未创建 Qdrant Client，未连接真实 Redis、RabbitMQ、Embedding 或 Qdrant；
- `git diff --cached --check` 通过，静态敏感信息检查扫描 621 个文本文件，无命中。

## Qdrant QV01-QV04 最终集成

### 提交与状态

- 共同基线：`3ec7a0fb629aac3d526ac03058b577b71f921a2b`；
- QV01 来源 `93fb4f8`，合入提交 `f99d04a`，状态 `AUTO_VERIFIED`；
- QV02 来源 `6e62e0d`，合入提交 `d9f2efb`，状态 `AUTO_VERIFIED`；
- QV03 来源 `3eb4efc`，合入提交 `b2c73d0`，状态 `AUTO_VERIFIED`；
- QV04 来源 `695ee73`，合入提交 `6e71324`，状态 `AUTO_VERIFIED`；
- 协调集成提交：`3457737`；
- 总体状态：`AUTO_VERIFIED / MANUAL_PENDING`。

### 已完成

- 当前 Redis + JVM HNSW 被封装为 baseline 后端，并修复相同 ID 覆盖和删除后旧 HNSW 命中；
- 接入官方 Qdrant Java Client、Collection/alias、payload index、服务端 scope 过滤、幂等写入和删除；
- 实现 `baseline`、`shadow`、`qdrant` 三种生产装配：baseline 不创建 Qdrant Client，qdrant 不构造/扫描本地 HNSW；
- 实现 Shadow 对比、失败隔离、指标，以及只在 shadow 模式提供的内部迁移服务；
- 本地后端支持稳定游标分页导出，迁移支持 dry-run、失败 checkpoint、恢复、数量核对和幂等重跑，不自动删除 Redis 源数据；
- RAG 检索在向量查询前传入 `userId + knowledgeBaseId`，文档删除改为一次带 scope 的后端删除；
- QV04 真实 provider 使用隔离 Redis DB 15、两个独立 Qdrant Client 和真实 Qdrant 重启；测试资源严格限制为 `qv04_test_*` 并在结束后清理。

### 验证结果

- QV04 专用集成入口：资产/Agent/RAG/MCP 110/110，真实双后端 49/49，合计 159/159，Failures 0、Errors 0、Skipped 0；
- 硬门禁：Qdrant Recall@5 1.0，baseline Recall@5 1.0，tenant leakage 0，delete residue 0，migration count difference 0；
- 迁移：源 60、成功 60、失败 0、目标 60，dry-run 写入 0，重跑后仍为 60，Redis 源未删除；
- 完整 `ai-service`：178 个测试，Failures 0、Errors 0、Skipped 3，`BUILD SUCCESS`；三个跳过项均为普通回归中隔离的真实基础设施测试，并已在专用入口执行通过；
- 配置层级回归：5/5 通过，确认 `ai.vector.migration.*` 未误嵌套到 `shadow`；
- 敏感信息检查：扫描 695 个 Git 可见文本文件，无命中；
- 清理复核：Qdrant `qv04_test_*` 资源 0，Redis DB 15 的 `vector:qv04-*` 和 `metadata:qv04-*` 残留均为 0；
- 完整回归使用非真实模型占位配置，四个远程模型实际成功仍为 0/4。

### 剩余边界

- 用户尚未执行 `docs/qdrant/06-HUMAN_ACCEPTANCE.md`，不得标记 `HUMAN_ACCEPTED`；
- 未使用真实远程 Embedding、真实用户文档或真实模型验证完整 RAG；
- 本机单节点合成性能不是生产容量、TLS/鉴权、网络隔离、HA、备份、灾备或长期压力证据；
- Qdrant 验收结束后已停止本次临时启动的 Qdrant 服务，Redis 保持任务开始前的既有运行状态。

## 维护规则

每次任务完成后更新本文件，至少记录：

- 本次形成的真实进展；
- 实际执行的测试或检查及结果；
- 尚未验证的事项和剩余风险；
- 下一步动作。

若没有运行或测试，应明确写“未运行”或“未验证”，不得推测结果。

## 历史文档基线验证（2026-07-26）

- `git diff --cached --name-status`：暂存区仅包含本次要求新增的四个文档。
- `git diff --cached --check`：通过，未发现空白错误。
- 业务测试：未运行；本次仅新增文档，不修改业务代码，运行基线仍待下一阶段验证。

## 变更记录

| 日期 | 阶段 | 记录 |
| --- | --- | --- |
| 2026-08-10 | Qdrant 自动验收 | QV01-QV04 按顺序集成并由协调提交 `3457737` 补齐生产装配；专用入口 159/159、完整回归 178 个测试无失败/错误、四项硬门禁和资源清理通过，人工验收待执行 |
| 2026-08-10 | Qdrant QV01-QV04 | 从共同基线 `3ec7a0f` 创建四个独立分支、工作树和 Codex 任务并开始并行开发；专项实现和真实 Qdrant 尚未验收 |
| 2026-08-09 | Qdrant QV00 | 冻结双后端目标、Java 契约、配置、任务边界与验收规则；新增确定性契约和 HNSW 基线测试，并发现覆盖写入和删除残留旧 HNSW 命中缺陷，交由 QV01 修复 |
| 2026-08-09 | MCP 搜索自动验收 | T01-T04 已按冻结顺序合并，补齐发现到工具目录的集成连接器；集成模式 78/78、完整回归 99/99、静态泄漏检查通过，真实 Tavily 与人工验收待执行 |
| 2026-08-09 | MCP 搜索 T01-T04 | 从共同基线 `61266db` 创建 4 个独立分支、Git 工作树和 Codex 任务，分别开始连接层、工具目录、Agent 路由与独立验收开发 |
| 2026-08-09 | MCP 搜索 T00 | 方案 A 已执行：原有 34 个文件审查后形成 checkpoint `51caddf`；T00 契约、测试基线和任务拆分已完成，真实 MCP 尚未接入 |
| 2026-07-26 | 需求冻结 | 初始化项目上下文、奖学金审查 V1 需求、协作约定与进展文档；未进行运行基线验证 |
