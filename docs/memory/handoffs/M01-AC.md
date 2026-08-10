# M01-AC Memory V1 验收资产交接

## 状态

- 任务：M01-AC 验收夹具、契约 Schema、统一入口与资产自检
- 分支：`test/memory-m01-acceptance`
- 起始基线：`37f81f7495f16c304b2b7b04dab1ab7783b67cae`
- 当前结论：`HANDOFF_READY`、`ASSET_VERIFIED`、`PENDING_INTEGRATION`
- 生产代码：未修改
- 自动验收结论：不得标记为 `AUTO_VERIFIED`

## 交付资产

- `ai-service/src/test/resources/memory/memory-v1-fixture.json`
  - 仅包含确定性合成数据；
  - 固定 8 条消息、连续序号、消息内容摘要、工具调用配对、来源引用、事实纠正与摘要覆盖窗口；
  - 显式声明不调用真实模型、远程 Embedding、MySQL、Redis 或 Memory Qdrant collection。
- `ai-service/src/test/resources/memory/memory-v1-summary-schema.json`
  - 冻结 Structured Conversation Summary 的严格 JSON Schema。
- `ai-service/src/test/resources/memory/memory-v1-report-schema.json`
  - 冻结机器报告的状态、边界、测试统计、P0 门禁、内容安全和未验证项字段。
- `ai-service/src/test/java/com/javaee/aiservice/memory/acceptance/*Test.java`
  - 只验证夹具与契约资产，不引用或替代尚未实现的生产 Memory V1 行为。
- `scripts/memory/run-m07-acceptance.cmd`
  - Windows 统一入口；默认运行资产验收，`-Integrated` 请求集成验收。
- `scripts/memory/run-m07-acceptance.ps1`
  - 运行资产测试和静态敏感信息检查；生成不含会话、摘要、文档、Prompt、选中文本、工具结果或凭据正文的机器报告。
- `scripts/memory/assert-integration-readiness.ps1`
  - 在生产实现或 M07 集成门禁缺失时明确输出 `PENDING_INTEGRATION` 并以非零状态结束。
- `scripts/memory/assert-no-sensitive-data.ps1`
  - 检查仓库可见文本中的常见密钥形态、夹具合成边界和机器报告正文边界。
- `scripts/memory/README.md`
  - 记录入口、结果语义、报告位置和安全边界。

## 已执行验证

### 资产测试

命令：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am "-Dtest=MemoryV1FixtureTest,MemoryV1SummarySchemaTest,MemoryV1ReportSchemaTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：集成协调修正可空 goal 契约后为 14/14 通过，0 failure，0 error，0 skipped，`BUILD SUCCESS`。

### 默认统一入口

命令：

```powershell
.\scripts\memory\run-m07-acceptance.cmd
```

结果：

- 14/14 资产测试通过；
- 静态敏感信息与正文边界检查通过；
- 机器报告写入 `ai-service/target/memory-v1-acceptance/memory-v1-report.json`；
- 报告状态为 `ASSET_VERIFIED`，同时明确生产行为为 `PENDING_INTEGRATION`；
- 入口返回成功仅表示验收资产可用，不表示 Memory V1 生产功能通过。

### Integrated 缺失路径

命令：

```powershell
.\scripts\memory\run-m07-acceptance.cmd -Integrated
```

结果：

- 14/14 资产测试仍通过；
- 明确列出 M02-M07 缺失的生产与集成证据；
- 机器报告状态为 `PENDING_INTEGRATION`，所有生产 P0 门禁保持 `null`；
- 进程以非零状态结束，因此不可作为最终通过结果。

## 报告内容边界

机器报告只保留 fixture id、消息数量、Schema 版本、Git/运行环境元数据、真假值边界、测试计数、门禁计数和未验证原因。报告不复制：

- 用户或 Assistant 消息正文；
- Structured Summary 内容；
- 文档正文、Prompt、选中文本或工具结果；
- API Key、Token、Cookie、数据库口令或私钥。

## 剩余风险与后续接入要求

- 本任务没有 MySQL、Redis、真实模型、浏览器、远程 Embedding 或 Qdrant 运行证据。
- Conversation/Message/Summary 持久化、幂等与并发序号、CAS 摘要、隔离、级联删除、token 预算及七条模型调用路径仍待 M02-M07 实现和集成验证。
- 目前 readiness 检查只证明生产源组和集成门禁是否存在；最终 P0/P1 结果必须由 M07 在集成后的源码和真实依赖边界上生成。
- M07 可复用本次 fixture、Schema 和统一入口，但必须将全部 P0 门禁从 `null` 收敛为真实计数，才可依据冻结标准决定是否允许 `AUTO_VERIFIED`。
