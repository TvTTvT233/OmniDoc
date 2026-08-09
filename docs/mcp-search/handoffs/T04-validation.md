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

## 本阶段交付

- 状态：`HANDOFF_READY`；
- 分支：`test/mcp-search-acceptance`；
- 共同基线：`61266dbbe268958c1e4d7ccbea9ccc5f15220ad8`；
- 提交：本次 T04 资产提交，ID 以最终交付说明为准；
- 真实 Tavily：`NOT_RUN`，当前测试进程未发现 `TAVILY_API_KEY`。

### 已建设资产

- `MockMcpServer`：loopback 随机端口、Bearer、Origin、会话、协议版本与 Streamable HTTP 头约束；
- 生命周期与协议：`initialize`、`notifications/initialized`、分页 `tools/list`、`tools/call`、DELETE 关闭；
- 对抗性工具目录：一个允许工具、三个白名单外工具、不可信 annotations、完整 Schema；
- 调用场景：structured content、文本回退、重复 URL、空结果、`isError=true`、401、超时、断网、非法 JSON、31000 字符结果；
- 9 个 fixture 自检；
- 当前基线与集成后两种运行模式；
- 静态密钥泄漏脚本；
- 自动测试矩阵和人工验收入口。

### 实际验证

命令：

```powershell
.\scripts\mcp-search\run-t04-acceptance.cmd
```

结果：44/44 通过，Failures 0、Errors 0、Skipped 0，BUILD SUCCESS。其中 fixture 9、MCP 契约 3、Agent/Controller 回归 32。静态检查扫描 570 个 Git 可见文本文件，无 Tavily Key 形态、URL 凭据、Bearer 字面量或私钥标记命中。

全量 `ai-service` 基线：首次只补 DashScope 占位值时 65 个测试中 1 个上下文错误，原因是缺少 `OPENAI_API_KEY`；补齐所有必填非真实占位变量后 65/65 通过。`AllModelsTest` 四个远程调用均为 401，实际模型成功 0/4，不能据此声称模型连通。

集成门禁：

```powershell
.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated
```

当前按设计退出 1：没有发现 T01-T03 非契约生产实现，状态为 `PENDING_INTEGRATION`。该结果证明门禁不会把缺失实现记作通过，不是产品失败。

### 待集成矩阵

| 范围 | 当前状态 | 归属 |
| --- | --- | --- |
| SDK 初始化、分页发现、调用与错误映射 | fixture 已就绪，生产 Client 待测 | T01 |
| allowlist、命名空间、Schema、冲突与本地风险策略 | 对抗性目录已就绪，映射待测 | T02 |
| RAG/Search/禁止联网路由 | 待生产路由集成 | T03 |
| 结果规范化、空结果、来源、Trace、超限上下文 | fixture 已就绪，Agent 断言待测 | T03 |
| MCP 开启/关闭后的完整回归 | 当前仅共同基线回归通过 | T01-T03 集成后 T04 |
| 日志、Trace、HTTP 运行时密钥复核 | 静态扫描通过，运行证据待生成 | T01/T03/T04 |
| 真实 Tavily 搜索 | 未执行 | 用户人工验收 |

### 风险与交接要求

- 当前不能标记 `AUTO_VERIFIED`，因为 T01-T03 尚未集成；
- 44/44 只覆盖 fixture、冻结契约和集成前回归，不能证明生产 MCP Client、工具映射、Agent 路由或真实 Tavily 可用；
- 集成后必须运行 `-Integrated`，逐项更新 `05-AUTO_TEST_REPORT.md`，不得只沿用本阶段数字；
- 若严格断言暴露缺陷，记录最小复现、预期/实际、脱敏证据和 T01/T02/T03 归属，不跨边界修改核心实现；
- 人工入口为 `docs/mcp-search/06-HUMAN_ACCEPTANCE.md`。
