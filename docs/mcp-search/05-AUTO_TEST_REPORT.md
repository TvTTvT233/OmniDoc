# MCP 搜索接入：自动测试报告

## T00 环境

- Java：Eclipse Adoptium 17.0.19
- Maven Wrapper：3.9.12
- OS：Windows 10 x64
- 分支：`docs/bootstrap-project-context`
- 起始 HEAD：`8427f59`
- 测试对象：包含进入 T00 前的工作树修改；这些修改随后保存为 checkpoint `51caddf`

## 基线测试 1：无补充环境变量

命令：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am test
```

结果：失败。

- Tests run：53
- Failures：0
- Errors：1
- 通过：52
- 失败测试：`AllModelsTest.testAllModels`
- 原因：Spring 上下文无法解析 `${DASHSCOPE_API_KEY}`

结论：默认测试命令依赖未提供的模型环境变量，当前不能作为开箱即用的绿色基线。

## 基线测试 2：测试进程占位环境变量

仅在该测试进程设置非真实占位值后执行相同命令。

结果：通过。

- Tests run：53
- Failures：0
- Errors：0
- BUILD SUCCESS

限制：

- `AllModelsTest` 实际尝试调用了四个远程模型；
- 占位 Key 下四个调用均认证失败，实际模型成功数为 0/4；
- 测试捕获异常且没有断言远程模型必须成功，因此 53/53 不能证明任何模型真实可用。

## 基线测试 3：MCP 改造直接相关测试

命令：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am "-Dtest=AgentExecutionServiceTest,AgentControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：通过。

- Tests run：32
- Failures：0
- Errors：0
- BUILD SUCCESS

该结果是 T01-T04 的主要回归基线。

## T00 其他检查

- `git diff --cached --name-status`：空；
- `git diff --check`：未发现空白错误，只出现既有 LF/CRLF 转换警告；
- T00 契约与直接回归测试：`AgentExecutionServiceTest`、`AgentControllerTest`、`McpContractTest` 共 35/35 通过，BUILD SUCCESS；
- 端到端服务启动：未执行；
- 真实 Tavily MCP：未执行；
- 网络、认证、超时和引用链路：待 T01-T04 实现后验证。

## T00 最终复验

- 日期：2026-08-09；
- 命令：`.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am "-Dtest=AgentExecutionServiceTest,AgentControllerTest,McpContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`；
- 结果：35/35 通过，Failures 0，Errors 0，Skipped 0，BUILD SUCCESS；
- 范围：MCP 供应商无关契约、现有 Agent 执行服务和 Agent Controller 回归；
- 限制：未连接真实 Tavily MCP，不能据此判断网络、认证或搜索结果质量。

## T04 阶段验收资产

- 日期：2026-08-09；
- 分支：`test/mcp-search-acceptance`；
- 共同基线：`61266dbbe268958c1e4d7ccbea9ccc5f15220ad8`；
- Java：Eclipse Adoptium 17.0.19；
- Maven Wrapper：3.9.12；
- OS：Windows 10 x64；
- 状态：`HANDOFF_READY`，不是 `AUTO_VERIFIED`。

新增的独立 `MockMcpServer` 只绑定 loopback 随机端口，按 MCP 2025-11-25 生命周期处理 `initialize`、`notifications/initialized`、分页 `tools/list` 和 `tools/call`。fixture 强制 Bearer、`MCP-Session-Id`、`MCP-Protocol-Version` 与 Streamable HTTP Accept 头，不记录 Bearer 值。

fixture 的 `tools/list` 故意返回：

- 允许的 `tavily-search`；
- 不允许的 `tavily-extract`、`tavily-crawl`、`tavily-research`；
- 包含 string、integer、number、boolean、array、enum、required、minimum、maximum、pattern 的完整 Schema；
- 不可信的 `destructiveHint=true`、`readOnlyHint=false` annotations。

fixture 的确定性调用场景覆盖 structured content、文本回退、重复 URL、空结果、`isError=true`、401、慢响应、服务停止/连接失败、非法 JSON 和超过 30000 字符的结果。以上表示服务端测试输入已经就绪，不表示 T01-T03 的客户端映射或 Agent 行为已通过。

## T04 当前可运行验证

统一入口：

```powershell
.\scripts\mcp-search\run-t04-acceptance.cmd
```

实际结果：

- Tests run：44；
- Failures：0；
- Errors：0；
- Skipped：0；
- 其中 fixture 自检 9、MCP 契约 3、原有 Agent/Controller 回归 32；
- BUILD SUCCESS；
- 静态泄漏检查扫描 570 个 Git 可见文本文件，无 Tavily Key 形态、URL 凭据、Bearer 字面量或私钥标记命中；
- 统一入口明确输出 `PENDING_INTEGRATION`。

限制：静态检查不等同于运行日志、Trace 或 HTTP 返回检查；后者必须在集成环境生成实际运行证据后复核。

## T04 全量基线复验

命令：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am test
```

只对测试进程设置非真实占位配置，不写入仓库或用户环境。

首次仅补 `DASHSCOPE_API_KEY` 占位值的实际结果：失败，Tests run 65、Failures 0、Errors 1；`AllModelsTest` 上下文因缺少 `OPENAI_API_KEY` 无法加载。该结果说明当前全量测试还依赖多个必填环境变量。

补齐 `DASHSCOPE_API_KEY`、`OPENAI_API_KEY`、MySQL、Redis、RabbitMQ、MinIO 所需的非真实占位变量后重跑：

- Tests run：65；
- Failures：0；
- Errors：0；
- Skipped：0；
- BUILD SUCCESS；
- `AllModelsTest` 的四个远程模型调用全部 401，成功 0/4；测试捕获异常，因此 65/65 不能证明任何真实模型连通。

该全量结果包含 T04 fixture 9 个测试；它验证共同基线没有额外测试回归，但仍不包含 T01-T03 生产 MCP 实现。

## T04 集成门禁验证

命令：

```powershell
.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated
```

当前实际结果：按设计失败，退出码 1。脚本检测到生产源码中只有冻结的 `mcp.contract`，没有 T01-T03 的非契约 MCP 实现，因此返回 `PENDING_INTEGRATION`，没有把缺失实现或缺失用例计作通过。

## 真实 Tavily 状态

- 当前测试进程未发现 `TAVILY_API_KEY`；
- 未发现 `TAVILY_MCP_BASE_URL` 或 `TAVILY_MCP_ENDPOINT` 覆盖；
- 未连接 `mcp.tavily.com`；
- 未执行真实搜索；
- 真实网络、认证、协议兼容性和搜索结果质量均为 `NOT_RUN` / 未验证。

## T04 验收矩阵

| 场景 | 当前证据 | 当前状态 | 集成后通过标准/归属 |
| --- | --- | --- | --- |
| MCP 关闭兼容 | 共同基线 Agent/Controller 32/32 | `PASS_BASELINE_ONLY` | 集成 T01-T03 后关闭 MCP 重跑且工具列表无 MCP；T01/T02/T03 |
| 初始化与 initialized 通知 | fixture 生命周期与会话自检 | `FIXTURE_PASS` | 生产 Sync Client 完成相同握手；T01 |
| 分页 `tools/list` | fixture 两页及 cursor 自检 | `FIXTURE_PASS` | 生产 Client 拉取全部页；T01 |
| `tools/call` 参数原样传递 | fixture 记录脱敏请求体 | `FIXTURE_PASS` | 生产 Gateway 对请求记录严格断言；T01 |
| allowlist | fixture 提供 1 个允许、3 个禁止工具 | `PENDING_INTEGRATION` | 最终仅暴露 `tavily-search`；T01/T02 |
| 命名空间与冲突 | 冻结契约存在 | `PENDING_INTEGRATION` | 仅 `mcp.web-search.tavily-search`，不能覆盖原生工具；T02 |
| Schema 必填与类型 | fixture 完整 Schema 自检 | `PENDING_INTEGRATION` | query 必填且各类型/约束不降级；T02/T03 |
| structured content 成功 | fixture 响应自检 | `PENDING_INTEGRATION` | 规范化结果、sources 与 MCP 元数据严格匹配；T01/T03 |
| 文本兼容回退 | fixture 响应自检 | `PENDING_INTEGRATION` | 无 structured content 时仍规范化成功；T01/T03 |
| 空结果成功 | fixture 响应自检 | `PENDING_INTEGRATION` | success 且最终回答明确未找到；T03 |
| `isError=true` | fixture 响应自检 | `PENDING_INTEGRATION` | 映射 `MCP_TOOL_ERROR`；T01/T03 |
| 401 | fixture 401 且不回显凭据 | `PENDING_INTEGRATION` | 映射 `MCP_AUTH_FAILED` 且不重试；T01/T03 |
| 超时 | fixture 客户端 50ms 超时自检 | `PENDING_INTEGRATION` | 映射 `MCP_TIMEOUT` 且等待有界；T01 |
| 断网/连接失败 | fixture 停止后连接失败自检 | `PENDING_INTEGRATION` | 映射 `MCP_UNAVAILABLE`，服务 fail-open；T01 |
| 协议错误 | fixture HTTP 200 + 非法 JSON 自检 | `PENDING_INTEGRATION` | 映射 `MCP_PROTOCOL_ERROR`；T01 |
| 过大结果 | fixture 31000 字符响应自检 | `PENDING_INTEGRATION` | 映射 `MCP_RESULT_TOO_LARGE`，不进入模型上下文；T01/T03 |
| 内部 RAG 路由 | 无集成实现 | `PENDING_INTEGRATION` | 材料问题不调用 MCP；T03 |
| 外部 Search 路由 | 无集成实现 | `PENDING_INTEGRATION` | 最新/联网问题选择 MCP；T03 |
| 禁止联网路由 | 无集成实现 | `PENDING_INTEGRATION` | Plan 与调用记录均无 MCP；T03 |
| 来源去重与最终引用 | fixture 提供重复 URL | `PENDING_INTEGRATION` | sources 稳定去重，答案 URL 为 sources 子集；T03 |
| Trace MCP 元数据 | 无集成实现 | `PENDING_INTEGRATION` | Server、远程/暴露工具、状态、耗时、错误码齐全；T03 |
| API Key 仓库静态泄漏 | 570 个文本文件扫描无命中 | `PASS_STATIC_ONLY` | 集成后重跑并补日志/Trace/HTTP 人工复核；T01/T03/T04 |
| 真实 Tavily 冒烟 | Key 不存在，未调用 | `NOT_RUN` | 用户安全提供 Key 后人工执行一次；人工验收 |

## 最终集成自动验收

本节记录主协调分支集成 T01-T04 后的最终结果，并取代上文 T04 独立分支阶段的 `PENDING_INTEGRATION` 状态。

- 日期：2026-08-09；
- 集成顺序：T02 → T01 → T03 → T04；
- 集成连接器提交：`ff9b619`；
- 状态：`AUTO_VERIFIED`；
- 真实 Tavily：`NOT_RUN`。

### T04 集成模式

命令：

```powershell
.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated
```

结果：

- Tests run：78；
- Failures：0；
- Errors：0；
- Skipped：0；
- BUILD SUCCESS；
- 静态泄漏检查扫描 588 个 Git 可见文本文件，无命中。

覆盖证据包括：SDK 初始化、分页发现、调用与错误映射；allowlist、命名空间、Schema、冲突保护和本地只读低风险策略；内部 RAG、外部 Search、禁止联网路由；structured/text/空/错误/超限结果；来源去重、最终引用和 Trace 元数据。新增跨层测试实际走通 T04 loopback 模拟 Server、T01 SDK Transport、T02 动态目录和 `tools/call`。

首次集成模式曾出现一个静态门禁失败：T01 单元测试中的固定 URL 查询参数值命中了“URL 凭据”规则。该值不是实际密钥；最终通过拆分测试数据构造消除仓库中的凭据形态字面量，未放宽扫描规则。

### 完整 `ai-service` 回归

仅在测试进程设置非真实占位配置后执行：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am test
```

结果：99/99 通过，Failures 0、Errors 0、Skipped 0，BUILD SUCCESS。`AllModelsTest` 的四个远程模型调用仍全部认证失败，实际成功 0/4；该结果只证明本地测试回归，不证明真实模型连通。

### 自动验收边界

- 真实 `TAVILY_API_KEY` 未提供；
- 未连接 `mcp.tavily.com`，未验证真实网络、认证兼容和搜索质量；
- 未执行完整外部基础设施下的服务启动；
- 日志、Trace、HTTP 返回和实际路由展示仍需按人工验收文档复核；
- 因此项目尚未标记 `HUMAN_ACCEPTED`。
