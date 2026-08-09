# Qdrant 向量后端接入：自动测试报告

## 当前状态

- QV04 验收资产：`HANDOFF_READY`；
- 独立分支资产运行：`ASSET_VERIFIED`；
- QV01-QV03 集成门禁：`PENDING_INTEGRATION`；
- 正式自动验收：尚未达到 `AUTO_VERIFIED`；
- 人工验收：`MANUAL_PENDING`。

独立分支没有 QV01 本地后端、QV02 Qdrant 后端和 QV03 Shadow/迁移实现。本页不会把测试替身、代码存在或静态检查写成真实双后端通过。

## 统一入口

独立资产与当前分支回归：

```powershell
.\scripts\qdrant\run-qv04-acceptance.cmd
```

QV01-QV03 合入后的完整门禁：

```powershell
$env:QV04_ACCEPTANCE_PROVIDER_CLASS = '集成任务提供的测试适配器完整类名'
.\scripts\qdrant\run-qv04-acceptance.cmd -Integrated
```

缺少任一生产源组或集成适配器时，`-Integrated` 非零退出并打印 `PENDING_INTEGRATION`。机器可读报告写入：

```text
ai-service/target/qv04-acceptance/qv04-run-report.json
```

真实集成运行还会写出 baseline、Qdrant、重启、Shadow、迁移分项 JSON。报告 Schema 位于 `ai-service/src/test/resources/qdrant/qv04-report-schema.json`。

## 确定性 fixture

`qv04-vector-fixture.json` 是完全合成数据：

| 项目 | 数值或边界 |
| --- | --- |
| 用户 | 3 |
| 每用户知识库 | 2 |
| 每知识库分段 | 10 |
| 总分段 | 60 |
| 文档 | 每知识库 2 个，每文档 5 段 |
| 向量维度 | 16 |
| 生成算法 | 固定 seed + SHA-256 + 归一化 |
| 远程 Embedding | 禁止，fixture 标记为 `false` |
| 跨租户高相似 | 6 个 scope 共享语义键并产生完全相同向量 |
| 其他场景 | 文档过滤、无答案、按 ID 删除、按文档删除 |

无答案查询保留空相关集，用于报告返回候选数量；底层 ANN 没有统一阈值契约，因此该项不作为“必须返回空”的硬门禁。

## QV04 资产和门禁

### 已在独立分支运行

- fixture 规模、合成边界、确定性向量和跨租户高相似自检；
- 共用 `VectorBackendAcceptanceHarness` 的两种后端类型测试替身自检；
- 幂等 upsert、scope 完整盘点、租户/知识库/文档隔离、按 ID 和按文档删除残留的门禁逻辑；
- Recall@K、MRR、Overlap@K、P50/P95 和写入吞吐计算；
- 两客户端可见性与重启持久化测试驱动自检；
- 报告 Schema 与测试 Collection 前缀策略；
- 当前分支 Agent、RAG、MCP 回归；
- Git 可见文本的密钥形态扫描和 QV04 fixture PII/合成边界检查。

测试替身结果只证明验收驱动能发现泄漏和删除残留，不证明 QV01/QV02 生产实现正确。

### 集成后由同一入口执行

`Qv04IntegratedAcceptanceProvider` 是 QV04 与未来实现之间的测试边界。主协调任务需要提供测试适配器，接入：

- 真实 `LocalRedisHnswVectorBackend` 与 `QdrantVectorBackend`；
- 两个独立 Qdrant Client 和真实 Qdrant 重启；
- baseline 不创建 Qdrant Client、qdrant 不扫描 Redis 重建索引的模式观测；
- Shadow 正常与 Qdrant 注入失败观测；
- 迁移 dry-run、失败批次 checkpoint、恢复、数量核对和幂等重跑观测。

只有该适配器运行全部通过，四项硬门禁才允许写入数值：

```text
Qdrant Recall@5 >= baseline Recall@5
tenant leakage = 0
delete residue = 0
migration count difference = 0
```

## 性能与质量报告字段

每个真实后端报告包含：

- 数据量、维度和查询数；
- Recall@5、MRR；
- tenant leakage、delete residue；
- 查询 P50/P95；
- 幂等双次写入的 records/s；
- JVM 内存增量；
- 是否为真实实现和证据级别。

生命周期报告包含两客户端可见性、重启持久化和重启耗时；Shadow 报告包含 Overlap@K、两侧耗时、错误码和样本数；迁移报告包含 dry-run、源/成功/失败/目标数量、checkpoint 和重跑数量。统一报告同时记录操作系统、处理器、物理内存、Java、Collection 名称和是否包含外部网络耗时。

第一阶段不要求 Qdrant 比 JVM HNSW 更快。单节点本机结果不得解释为生产容量、分布式、高可用或故障恢复证明。

## 独立分支实际结果

执行命令：

```powershell
.\scripts\qdrant\run-qv04-acceptance.cmd
```

结果：

```text
Tests run: 110
Passed: 110
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

其中 QV04 fixture/驱动/指标/报告/Collection 边界及冻结契约为 23/23；当前分支 Agent/RAG/MCP 回归为 87/87。静态检查扫描 639 个 Git 可见文本文件，无密钥形态命中；两个本次生成的 Collection/alias 名称均满足 `qv04_test_` 前缀。

执行集成入口：

```powershell
.\scripts\qdrant\run-qv04-acceptance.cmd -Integrated
```

实际非零退出并报告缺少：

```text
QV01_LOCAL_BACKEND
QV02_QDRANT_BACKEND
QV03_ROUTING
QV03_MIGRATION
QV04_INTEGRATED_PROVIDER_CLASS
PENDING_INTEGRATION
```

因此本次真实双后端测试数为 0，真实 Qdrant 连接/重启为 0，真实迁移和 Shadow 样本为 0，远程 Embedding 调用为 0。Recall@5、MRR、Overlap@K、P50/P95、吞吐、启动/重启和内存的真实后端值均保持未验证。
