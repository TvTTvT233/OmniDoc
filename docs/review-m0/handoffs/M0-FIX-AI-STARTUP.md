# Review M0 F2：ai-service MCP Bean 启动修复交接

## 1. 结论

- 状态：`HANDOFF_READY / AUTO_VERIFIED_WITHIN_F2_SCOPE`
- 修复结果：`McpToolCatalogAdapter` 的生产注入构造器现为唯一、显式且可测试；`agent.mcp.enabled=false` 的打包 `ai-service` Jar 已达到 HTTP ready，远程 MCP 请求为 0，并通过 Spring shutdown hook 正常关闭。
- MCP enabled 路径保持：真实 SDK 模拟链的初始化、分页发现、allowlist、目录刷新、server 隔离移除和工具路由回归通过。
- P-08：保持 `LOCAL_ONLY`。
- P-09：保持 `NOT_RUN / PENDING`。
- 本任务未修改 Review 数据库、文件链、日志策略、公共配置、POM、UI/路由、远程模型或 document/OCR。

## 2. Git、分支与隔离

| 字段 | 实际值 |
| --- | --- |
| Git 根 | `C:\Users\lab\Desktop\docAI-main\OmniDoc` |
| 固定基线 | `fa7f1d8f8b3df984db84681bd15e55bd96c9a127` |
| 基线分支核对 | `milestone/review-m0` 精确指向固定基线 |
| F2 分支 | `fix/review-m0-f2-ai-startup-01a03965` |
| F2 worktree | `C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-f2-ai-startup-01a03965` |
| 预交接验证提交 | `b1d7afb8a24247c9ab31966e26c8f1055564e856` |
| 最终 cherry-pick 提交 | 由最终交付回执给出完整 SHA；Git 提交不能在自身内容中稳定自引用自身 SHA |

开始前已完整读取根 `AGENTS.md`、三份项目上下文、`docs/review-m0/README.md` 与 00..04 全部冻结文档、`M0-R3-FINAL-GATE.md`，并通过 `git show` 完整读取指定 R4-A/R4-B handoff。共享 checkout、R4 验收 worktree及其他修复 worktree均未修改。

## 3. 修改文件

1. `ai-service/src/main/java/com/javaee/aiservice/agent/execution/tool/McpToolCatalogAdapter.java`
   - 在现有 public 单参生产构造器上显式标注 `@Autowired`。
   - package-private 双参测试便利构造器保持不变，不再参与 Spring 构造器选择。
2. `ai-service/src/test/java/com/javaee/aiservice/agent/execution/mcp/ProductionBeanConstructionTest.java`
   - 断言恰好一个显式生产注入构造器，参数精确为 `AgentToolRegistry`。
   - 以 `fail-fast=true` 和不可连接 server 配置验证 MCP disabled 上下文启动且不连接远程。
3. `docs/review-m0/handoffs/M0-FIX-AI-STARTUP.md`
   - 本交接；没有更新被任务明确禁止修改的 `docs/PROGRESS.md`。

## 4. 设计不变量

- 生产 Bean 仍由组件扫描创建，没有排除 Agent 包、删除 MCP 能力或增加旁路配置。
- `McpToolDefinitionMapper` 的现有构造和映射行为不变；生产依旧由单参构造器创建 mapper。
- 未按 `agent.mcp.enabled` 条件化 adapter Bean，因此没有改变现有 Bean 拓扑；`ObjectProvider` 和 lifecycle 的缺席安全分支保留原状。
- disabled 时沿用 `SdkMcpToolGateway.start()` 的既有首段短路：不创建 client、不初始化、不发现、不路由远程 MCP。
- enabled 时沿用原 lifecycle phase：gateway 先启动，catalog 后发布；停止时先移除动态工具再关闭 client。
- 目录刷新失败保留旧快照、单 server 移除不影响原生/其他 server 工具、MCP 工具路由与结果语义均未改变。
- Review M0 冻结边界未变：齐全性+文件命名、逻辑 `submissionFileName`、六类顶层格式、ZIP 仅容器、上传未确认不得 `MISSING`、`CANNOT_CONFIRM != FAILED`、报告不可变/`OUTDATED`、owner-null fail closed。

## 5. 修复前复现与打包 Jar 启动

修复前从固定基线真实打包 Jar，使用 `agent.mcp.enabled=false` 启动：

- 进程退出码 `1`；
- 端口未 ready；
- 精确失败 Bean 为 `mcpToolCatalogAdapter`；
- 稳定原因是两个非默认构造器没有唯一 Spring 注入选择，最终报 `No default constructor found`。

修复后重新打包并使用隔离端口 `38085` 启动。为了只验 F2，命令行显式关闭 Memory 持久化和 Nacos 自动注册；这两个默认外部前置分别在前两次启动尝试中被真实发现，但未通过改代码或公共配置处理：

- 日志明确出现 `MCP client is disabled; no remote clients were created`；
- Tomcat 在 `38085` 启动，`AiServiceApplication` 报 `Started`；
- 对受保护 Review 端点的真实 HTTP/1.1 请求返回 `403`，证明 Servlet 链 ready；
- 同时运行的 `127.0.0.1:38100` 计数型 MCP 目标收到请求 `0`；
- 控制台中断触发 `SpringApplicationShutdownHook`，broker 停止，随后 `38085` 与 `38100` 监听均为 `0`。

启动期间现有 Redis 定时指标任务记录不可连接错误，但不阻止 HTTP ready；本任务未修改其日志或策略。第一次未关闭 Memory 的尝试因无真实 MySQL 失败，第二次未关闭 Nacos 注册的尝试因无 Nacos 失败；二者都是启动参数前置发现，不是 F2 回归失败，也未被写成外部依赖通过。

## 6. 自动验证结果

计数顺序为 `discovered / passed / failures / errors / skipped`。

| 验证 | 实际结果 | 状态/边界 |
| --- | --- | --- |
| F2 显式构造 + enabled/disabled 配置上下文 | `3 / 3 / 0 / 0 / 0` | disabled 不连接；enabled 不可用 server 按既有 fail-open 启动 |
| MCP Search Integrated | `80 / 80 / 0 / 0 / 0` | 含真实 SDK 模拟 server、发现、目录、移除、路由、Agent/Controller；真实 Tavily `NOT_RUN` |
| Review 默认资产门 | `26 / 26 / 0 / 0 / 0` | `ASSET_VERIFIED`，不冒充生产 Review 验收 |
| Review M0-06 API/config | `23 / 23 / 0 / 0 / 0` | H2/MySQL-mode 合成边界；真实 MySQL `NOT_RUN` |
| Memory V1 Integrated | `187 / 187 / 0 / 0 / 0` | 资产 16、Integrated 171；其中 M06 前端 42、Agent/Controller regression 34；`AUTO_VERIFIED / MANUAL_PENDING` |
| 完整生产 ApplicationContext (`AllModelsTest`) | `1 / 1 / 0 / 0 / 0` | 四个远程模型禁用，真实模型成功 `0/4` |
| 打包 Jar | package `BUILD SUCCESS`；HTTP ready `1/1`；远程 MCP 请求 `0` | Memory/Nacos 仅由启动参数关闭；真实外部服务未运行 |

`npm ci` 成功并安装 284 个包；现有审计结果为 12 项（3 moderate、7 high、2 critical），本任务未升级依赖或修改 lockfile。Web 生产构建成功，保留现有大 chunk 警告。

## 7. 实际命令

以下命令均从 F2 worktree 根执行；环境变量只使用非真实占位值，未记录任何真实凭据：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 `
  '-Dtest=ProductionBeanConstructionTest,McpClientConfigurationTest' test

.\scripts\mcp-search\run-t04-acceptance.cmd -Integrated
.\scripts\review-m0\run-m0-acceptance.cmd `
  -ReportDirectory ai-service\target\f2-review-default

.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 `
  '-Dtest=com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test' test

Push-Location web
npm.cmd ci
Pop-Location
.\scripts\memory\run-m07-acceptance.cmd -Integrated `
  -ReportDirectory ai-service\target\f2-memory

.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 `
  '-Dtest=AllModelsTest' '-Dagent.mcp.enabled=false' `
  '-Dai.models.openai[qwen3.7-max-2026-05-20].enabled=false' `
  '-Dai.models.openai[glm-5].enabled=false' `
  '-Dai.models.openai[kimi-k2.5].enabled=false' `
  '-Dai.models.openai[MiniMax-M2.5].enabled=false' `
  '-Dspring.rabbitmq.listener.simple.auto-startup=false' test

.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am -DskipTests package

python -m http.server 38100 --bind 127.0.0.1
java -Xms32m -Xmx384m -jar ai-service\target\ai-service-1.0.0.jar `
  --server.port=38085 --ai.memory.enabled=false `
  --spring.cloud.nacos.discovery.enabled=false `
  --spring.cloud.service-registry.auto-registration.enabled=false `
  --agent.mcp.enabled=false --agent.mcp.fail-fast=true `
  --agent.mcp.servers.web-search.enabled=true `
  --agent.mcp.servers.web-search.base-url=http://127.0.0.1:38100 `
  --agent.mcp.servers.web-search.endpoint=/mcp/ `
  --agent.mcp.servers.web-search.api-key=not-a-real-key `
  --agent.mcp.servers.web-search.namespace=mcp.web-search `
  --agent.mcp.servers.web-search.allowed-tools[0]=tavily-search `
  '<four model enabled=false arguments>' `
  --spring.rabbitmq.listener.simple.auto-startup=false
```

## 8. 真实依赖与 NOT_RUN

| 依赖/边界 | 状态 |
| --- | --- |
| Java 17、真实打包 Spring Boot Jar、真实本地 HTTP socket | `RUN / PASSED` |
| 本地计数型 MCP HTTP 目标 | `RUN / 0 REQUESTS` |
| MCP enabled 模拟 server + MCP Java SDK | `RUN / PASSED` |
| 真实 Tavily MCP | `NOT_RUN` |
| 真实 MySQL 8 / Review V001-V003 | `NOT_RUN`；不得继承或声称 R4-A 通过 |
| 真实 MinIO / file-service / JWT 文件链 | `NOT_RUN`；不得继承或声称 R4-B 通过 |
| 真实 Redis / RabbitMQ / Nacos | `NOT_RUN` |
| 真实 document-service / OCR 网络链 | `NOT_RUN` |
| 真实远程模型 / Embedding / Qdrant | `NOT_RUN`；模型成功 `0/4` |
| 真实浏览器 / UI / P-09 | `NOT_RUN / PENDING` |

## 9. 剩余风险

- 本修复只消除生产 Bean 构造歧义；不解决 R4-A 的真实 MySQL 索引长度失败、R4-B 的文件完整性 fail-closed 与日志问题，也不恢复任何 R4-A/B/C/D 结果。
- 打包 Jar 的 HTTP-ready 验收使用 Memory disabled、Nacos 自动注册 disabled，真实 MySQL/Redis/Nacos 组合启动仍需各自任务验证。
- MCP enabled 自动回归使用本地模拟 server；真实 Tavily 鉴权、网络和供应商行为仍未验证。
- npm audit 与现有前端 chunk 警告不在本任务依赖升级范围。

## 10. 集成顺序与复跑

本提交可独立 cherry-pick 到 `fa7f1d8f8b3df984db84681bd15e55bd96c9a127` 或其包含该基线的后继集成分支。建议顺序：

1. cherry-pick F2；
2. 先复跑 `ProductionBeanConstructionTest,McpClientConfigurationTest`；
3. 复跑 MCP 80/80、Review 默认门与 M0-06/config；
4. 复跑 Memory Integrated 和 `AllModelsTest`；
5. 用上节相同 LOCAL_ONLY 参数重跑打包 Jar HTTP-ready/零 MCP 请求/关闭；
6. 之后由独立任务重新开始 R4-A/R4-B 的真实依赖验收，不能继承本 handoff 的 `NOT_RUN` 项。

本任务未发现冻结契约冲突。最终提交前需再次执行 `git diff --check`、允许文件范围审计、提交范围审计和 clean-status 检查。
