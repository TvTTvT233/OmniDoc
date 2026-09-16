# T00 交接：基线、契约与任务拆分

## 状态

`HANDOFF_READY`：文档、契约、测试基线和方案 A checkpoint 已完成，可以从共同基线创建 T01-T04 独立任务。

## 基线

- 分支：`docs/bootstrap-project-context`
- T00 起始 HEAD：`8427f59`
- T00 起始暂存区：空
- T00 起始已跟踪修改：18 个文件
- T00 起始未跟踪文件：16 个
- 基线 checkpoint：`51caddf`（34 个文件）

## 完成内容

- 修复仓库精确路径的 Git `safe.directory`；
- 形成目标、架构、冻结契约、任务看板、集成日志、自动测试、人工验收文档；
- 形成 T01-T04 独立任务交接；
- 新增供应商无关的 MCP Java 契约层；
- 新增契约测试；
- 更新 `docs/PROGRESS.md`。

## 修改文件

- `docs/PROGRESS.md`；
- `docs/mcp-search/**`；
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/mcp/contract/**`；
- `ai-service/src/test/java/com/javaee/aiservice/agent/execution/mcp/contract/McpContractTest.java`。

## 验证

- 默认完整测试：53 个测试中 52 个通过，1 个因缺少 `DASHSCOPE_API_KEY` 加载失败；
- 测试进程占位配置：53/53 通过，但真实模型调用 0/4，不能证明模型可用；
- T00 最终直接回归：35/35 通过；
- MCP 契约测试：3/3 通过；
- 密钥模式检查：通过；
- `git diff --check`：通过，只有既有 LF/CRLF 警告。

## 未验证

- 真实 Tavily MCP；
- MCP 初始化、发现、调用、超时和认证；
- 服务端到端启动；
- T01-T04 尚未创建或执行。

## 已执行的基线决策

用户于 2026-08-09 选择方案 A：checkpoint 当前已有工作。

- 已审查进入 T00 前已有的修改和常见敏感信息模式，未发现真实密钥、Token 或私钥；
- 已将 18 个已跟踪修改和 16 个未跟踪文件形成 checkpoint `51caddf`；
- T00 文件保持为独立提交；
- 后续所有独立工作树应从包含上述两次提交的共同基线开始。

## 下一步

创建 T01-T04 独立 Codex 任务和工作树，按各自交接文档开发与验收；当前尚未创建。
