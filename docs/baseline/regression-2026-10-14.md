# 后端回归与构建证据（2026-10-14）

状态：已通过无外部模型的构建与确定性回归；完整运行基线以主协调验收为准。

## 基线与工具

- 分支 `integration/core-baseline`，起始 HEAD `ca3423a73bc7bc15190978989e2105b30298a558`。
- 真实启动发现并修复 MCP 构造器注入缺陷，修改一个生产类和一个配置回归测试；POM 未改。工作区存在协调文档和前端改动；本报告不声称干净发布版本。
- 便携 Azul Zulu JDK 17.0.18+8：`.local/tools/zulu17.64.17-ca-jdk17.0.18-win_x64`。只在验证进程设置 JAVA_HOME/PATH，未修改系统默认 Java 25。
- Maven Wrapper 3.9.12，使用仓库 `.mvn/settings-local.xml`。日志和 Surefire 原始证据存放 `.local/baseline-evidence/`，均在 Git 忽略路径。
- JDK 下载来源为 Azul 官方 CDN；归档 SHA-256 `AA2F7455B3944DD37B0CA333B4D6DD41708C41CFEA1889E97F09C8A851984BDD`。Temurin 下载过慢后取消；未执行不完整下载。

## 已实际执行

所有命令在仓库根目录、上述 JDK 17 进程环境运行。

| 验证 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| 全模块打包 | `./mvnw.cmd -B -s .mvn/settings-local.xml -DskipTests package` | 退出 0；7 个 Reactor 模块全部 SUCCESS；测试明确跳过 | `backend-package.log` |
| MCP 集成 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcp-search/run-t04-acceptance.ps1 -Integrated` | 退出 0；发现/通过 80，失败/错误/跳过 0；静态泄漏检查 867 个文本文件无命中 | `mcp-integrated.log`、`mcp-surefire/` |
| AI 本地全量回归 | `./mvnw.cmd -B -s .mvn/settings-local.xml -pl ai-service -am '-Dtest=*,!AllModelsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 退出 0；发现 291，实际执行通过 288，跳过 3，失败/错误 0 | `ai-deterministic.log`、`ai-deterministic-surefire/` |
| 文档服务单元测试 | `./mvnw.cmd -B -s .mvn/settings-local.xml -pl document-service -am '-Dtest=DocumentServiceImplTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 退出 0；4/4，失败/错误/跳过 0 | `document-unit.log`、`document-surefire/` |

MCP 80 项与 AI 全量存在重复，不可相加宣称独立覆盖数量。

## 证据边界与未执行项

- 用户本轮明确不接外部模型。`AllModelsTest` 通过测试选择明确排除，未注入真实模型凭据；该类内部会捕获模型调用异常而不令 JUnit 失败，因此不能用它的零退出说明模型可用。
- MCP 验证使用仓库本地模拟 HTTP Server，真实 SDK/协议链执行；不代表 Tavily 外部服务通过。
- Memory/JDBC 等确定性验证使用 H2 MySQL mode、受控 Redis/模型替身；不代表真实 MySQL/Redis/模型端到端验证。
- AI 全量跳过的三项是 `Qv04IntegratedGateTest`、`QdrantPersistenceIntegrationTest`、`QdrantVectorBackendIntegrationTest`。普通测试未启用真实 Qdrant 集成，跳过不计通过；这三项随后在专用真实双后端门禁实际执行通过，见后续记录。
- `common` 的 `JwtUtilsTest` 是空类；用户/文件/网关服务仅有应用上下文 smoke test，文档服务另有 context smoke。未将这些外部依赖上下文测试混入确定性单测通过计数，实际服务启动交由运行基线执行。
- Memory M07 最终门禁要求运行前后 Git 工作区干净，等待主协调收齐修改并固定候选后执行；不削弱原门禁。

## 构建产物

以下五个可启动 Spring Boot JAR 已生成并交给运行基线执行者：

- `user-service/target/user-service-1.0.0.jar`
- `file-service/target/file-service-1.0.0.jar`
- `document-service/target/document-service-1.0.0.jar`
- `ai-service/target/ai-service-1.0.0.jar`
- `gateway-service/target/gateway-service-1.0.0.jar`

`common/target/common-1.0.0.jar` 为共享库，不能算第六个服务。打包成功不能替代服务健康或业务验收。

## 实际启动阻塞的最小修复

- 运行执行者启动 AI 服务时报告 `McpToolCatalogAdapter: No default constructor found`。类有公共单参数和包内双参数构造器，Spring 没有明确的注入选择。
- 既有 `McpClientConfigurationTest` 使用手工 `@Bean -> new` 绕过了组件实例化。现改为 `@Import(McpToolCatalogAdapter.class)`，由 Spring 执行真实实例化。
- 修复前专项实际退出 1，1 个测试失败，同样复现无默认构造器错误，记录 `mcp-wiring-before.log`。生产修复仅为公共注入构造器添加 `@Autowired`。
- 修复后执行 `./mvnw.cmd -B -s .mvn/settings-local.xml -pl ai-service -am '-Dtest=McpClientConfigurationTest,McpToolCatalogAdapterTest' '-Dsurefire.failIfNoSpecifiedTests=false' package`，退出 0，8/8，失败/错误/跳过 0；AI JAR 已重新打包并交给运行执行者。记录 `mcp-wiring-after-package.log`。
- 修复后再次运行上表 AI 全量命令，退出 0，291 项发现、288 执行通过、3 项在普通入口跳过。记录 `ai-deterministic-after-fix.log` 和 `ai-deterministic-after-fix-surefire/`。
- 此修复不改变 MCP 工具定义、调用或安全策略，不增加远程模型调用。

## 真实 Redis / Qdrant 双后端专项

- 命令：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/qdrant/run-qv04-acceptance.ps1 -Integrated -ProviderClass com.javaee.aiservice.rag.vector.qdrant.Qv04ProductionAcceptanceProvider -ReportDirectory D:\OmniDoc\.local\baseline-evidence\qv04`。
- 环境仅在当前进程设置：`COMPOSE_PROJECT_NAME=omnidoc-baseline-20261014`，`JAVA_TOOL_OPTIONS=-Dqv04.redis.port=16379 -Dqdrant.grpc-port=16334`；隔离 Redis 密码从本机被忽略的 runtime.env 读取，未写入报告。原测试内部 Compose 重启命令通过项目名定位到隔离 qdrant 服务。
- 实际退出 0，161/161：112 项资产/Agent/RAG/MCP + 49 项真实双后端选择，失败/错误/跳过全部 0。包含真实 Redis DB15、两个独立 Qdrant Client、真实 Qdrant 重启持久化、Shadow 与迁移。
- 硬门禁：两后端 Recall@5 均为 1.0，tenant leakage 0，delete residue 0，migration count difference 0；迁移源 60、成功 60、失败 0、目标 60、重跑目标仍 60、dry-run 写入 0、源数据未删除。
- 结束后独立清理复核：`qv04_test_*` Collection 0，Redis DB15 `vector:qv04-*` 0、`metadata:qv04-*` 0。记录 `qv04-cleanup.json`。
- 证据：`qv04-integrated.log`、`qv04/qv04-run-report.json`、`qv04-surefire/`。JSON 中 Java 字段受 JAVA_TOOL_OPTIONS 提示行影响只记录了 JVM 参数；实际工具版本以上述 JDK 17.0.18 验证为准，未伪改原报告。
- 这是 60 条、16 维确定性 SHA-256 合成向量的本地单节点门禁，未访问真实 Embedding 或外部模型；不替代真实文档 RAG、生产容量/高可用和用户简历指标的原始测量。
