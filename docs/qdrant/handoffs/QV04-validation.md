# QV04 交接：独立自动验收与人工验收资产

## 状态

- QV04 资产交付：`HANDOFF_READY`；
- QV01-QV03 真实集成门禁：`PENDING_INTEGRATION`；
- 正式自动验收：尚未达到 `AUTO_VERIFIED`；
- 用户人工验收：`MANUAL_PENDING`。

本分支已完成独立可审查的验收资产。QV01-QV03 生产实现不在本分支，真实双后端、Qdrant、Shadow、迁移与重启没有被测试替身或静态检查计作通过。

## 基线与范围

- 分支：`test/qdrant-vector-acceptance`；
- 起始 HEAD：`3ec7a0fb629aac3d526ac03058b577b71f921a2b`；
- 起始工作区与暂存区：干净；
- 交付提交：包含本文件的单一 QV04 提交，最终哈希见交接回复；
- 未修改生产实现、`rag/vector/contract/**`、`docs/PROGRESS.md`、任务板或集成日志。

## 完成内容

### 确定性 fixture

- 完全合成、无真实用户数据；
- 3 用户 × 每用户 2 知识库 × 每库 10 段，共 60 段；
- 每知识库 2 个文档、每文档 5 段，覆盖同文档多段；
- 16 维向量由固定 seed、SHA-256 和归一化在进程内生成；
- 6 个 scope 共享语义键并生成相同向量，形成强跨租户/跨知识库隔离压力；
- 包含文档过滤、无答案、按 ID 删除和按文档删除场景；
- fixture 明确 `remoteEmbedding=false`，未调用远程 Embedding。

### 自动验收资产

- `VectorBackendAcceptanceHarness` 对任意 `VectorBackend` 执行同一套幂等 upsert、全 scope 盘点、租户/知识库/文档隔离、质量查询和删除残留门禁；
- 两种后端类型的进程内测试替身仅用于验证验收驱动自身，并明确标记 `HARNESS_SELF_TEST_ONLY`；
- 负向自检证明驱动能发现跨租户返回和删除残留；
- 生命周期驱动覆盖两个独立客户端可见性和重启后持久化；
- `Qv04IntegratedAcceptanceProvider` 为主协调任务提供稳定测试适配边界，覆盖真实双后端、模式、副作用、Shadow 和迁移；
- 集成测试冻结四项硬门禁：Qdrant Recall@5 不低于 baseline、租户泄漏 0、删除残留 0、迁移数量差 0；
- 集成测试还验证 baseline 不创建 Qdrant Client、qdrant 不扫描 Redis、Shadow 主结果不变/失败隔离、dry-run、checkpoint、恢复和幂等重跑；
- 统一入口包含当前分支 Agent、RAG、MCP 回归；集成后同一入口会在已合入生产代码上重跑。

### 报告、安全和 Collection 边界

- 计算并输出 Recall@5、MRR、Overlap@K、P50/P95、写入 records/s、JVM 内存增量、两客户端可见性和重启耗时；
- 机器报告记录数据量、维度、OS、处理器、物理内存、Java、Collection 和外部网络边界；
- 静态扫描 Git 可见文本中的 provider key、Bearer、URL credential、私钥和云密钥形态；
- 额外检查 fixture 的合成标志、远程 Embedding 标志、QV04 ID namespace，以及邮箱/手机号/身份证形态；
- Java 与 PowerShell 两层要求所有测试 Collection/alias 以 `qv04_test_` 开头，并拒绝稳定 alias、生产名称和不安全字符；
- 统一入口本身不删除 Collection，禁止自动清理非测试前缀。

### 人工验收

- `06-HUMAN_ACCEPTANCE.md` 已提供自动报告复核、Baseline、隔离、Shadow、迁移、正式 Qdrant、两客户端、重启、删除和回滚步骤；
- 提供证据要求、人工记录表和结论规则；
- `AUTO_VERIFIED` 前不得开始正式人工签署，全部用户场景确认前保持 `MANUAL_PENDING`。

## 统一入口

独立资产与当前分支回归：

```powershell
.\scripts\qdrant\run-qv04-acceptance.cmd
```

主协调任务集成 QV01-QV03 并提供测试适配器后：

```powershell
$env:QV04_ACCEPTANCE_PROVIDER_CLASS = '集成测试适配器完整类名'
.\scripts\qdrant\run-qv04-acceptance.cmd -Integrated
```

缺少 QV01-QV03 或适配器时，集成入口非零退出并报告 `PENDING_INTEGRATION`，四项真实硬门禁在 JSON 中保持 `null`。

## 实际验证

### 独立统一入口

```text
Tests run: 110
Passed: 110
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- QV04 fixture/驱动/指标/报告/Collection 边界和冻结契约：23/23；
- 当前分支 Agent/RAG/MCP 回归：87/87；
- 未运行 `AllModelsTest`，避免把占位 Key 的远程模型失败或网络行为混入 QV04；
- 远程 Embedding 调用：0；
- 真实 Qdrant 调用：0。

### 静态与边界检查

- 敏感信息检查：639 个 Git 可见文本文件，无命中；
- QV04 fixture 合成/PII/远程 Embedding 边界：通过；
- 本次生成的 Collection 与 alias 两个名称均满足 `qv04_test_` 前缀；
- 报告状态：`ASSET_VERIFIED`，四项真实硬门禁为 `null`。

### 集成负向门禁

`run-qv04-acceptance.cmd -Integrated` 在完成独立资产与回归后非零退出，准确报告：

```text
QV01_LOCAL_BACKEND
QV02_QDRANT_BACKEND
QV03_ROUTING
QV03_MIGRATION
QV04_INTEGRATED_PROVIDER_CLASS
PENDING_INTEGRATION
```

没有把缺失实现计为通过。

## 修改文件

- `ai-service/src/test/resources/qdrant/qv04-vector-fixture.json`；
- `ai-service/src/test/resources/qdrant/qv04-report-schema.json`；
- `ai-service/src/test/java/com/javaee/aiservice/rag/vector/acceptance/**`；
- `scripts/qdrant/**`；
- `docs/qdrant/05-AUTO_TEST_REPORT.md`；
- `docs/qdrant/06-HUMAN_ACCEPTANCE.md`；
- 本交接文件。

## 未验证与剩余风险

- QV01-QV03 尚未集成，真实本地后端和 Qdrant 后端契约测试为 0；
- 未启动、连接或重启真实 Qdrant，未验证 Collection、alias、payload filter、持久化或两个真实客户端；
- 未运行真实 Shadow 或迁移，Recall@5、MRR、Overlap@K、P50/P95、吞吐、启动/重启和内存没有真实后端数值；
- 未验证 baseline 启动不创建 Qdrant Client、qdrant 启动不扫描 Redis；
- 未调用远程 Embedding，未使用真实用户数据；
- 单节点本地验收未来即使通过，也不能作为生产容量、高可用或真实数据安全证明；
- 集成适配器由主协调任务补齐，必须连接受控本地 Qdrant 或测试容器并复用现有生产装配，不得用第二套假实现替代。

## 下一步

1. 主协调任务依次合入 QV01、QV02、QV03 和本提交；
2. 新增实现 `Qv04IntegratedAcceptanceProvider` 的测试适配器；
3. 使用受控本地 Qdrant/测试容器执行 `-Integrated`，核对机器报告和所有分项 JSON；
4. 只有完整门禁通过后更新为 `AUTO_VERIFIED`；
5. 用户按 `06-HUMAN_ACCEPTANCE.md` 完成验收后才能更新为 `HUMAN_ACCEPTED`。
