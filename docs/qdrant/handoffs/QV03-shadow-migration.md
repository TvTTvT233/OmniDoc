# QV03 交接：Shadow、迁移与切换

## 状态

`HANDOFF_READY`

QV03 已在冻结 `VectorBackend`、`VectorBackendRouter` 和 `VectorRecordExporter` 接口上完成独立实现与确定性测试。独立分支没有 QV01/QV02 生产实现，因此本交接不把真实 Redis、Qdrant、Embedding 或完整 RAG 联调写成已验证。

## 基线

- 分支：`feature/qdrant-shadow-migration`；
- 起始 HEAD：`3ec7a0fb629aac3d526ac03058b577b71f921a2b`；
- 起始工作区：干净；
- 交付提交：本 handoff 与实现位于同一个可审查提交，完整哈希由最终交付报告记录。

## 三模式路由

生产入口为 `DefaultVectorBackendRouter`：

- `baseline`：只调用 `LOCAL_REDIS_HNSW`，`type()` 返回本地后端；Qdrant 通过惰性 `Supplier<VectorBackend>` 提供，baseline 路径不解析 Supplier、不创建 Client；
- `shadow`：写入、批量写入和删除先完成 baseline，再在有界后台执行 Qdrant 副本；查询按采样率启动 Qdrant 对照并立即返回原始 baseline 结果对象；任何 Qdrant 超时或异常只进入 Shadow 指标，不改变或替代主结果；
- `qdrant`：写入、查询、删除和健康检查只调用 Qdrant，`type()` 返回 `QDRANT`；缺失、错误类型或失败的 Qdrant 后端会显式抛出稳定错误，不存在 baseline 静默回退；
- 默认仍由冻结配置 `ai.vector.mode=baseline` 控制；Shadow 默认 `sample-rate=1.0`、`timeout-ms=3000`，取值分别强制为 0-1 和正数；
- 回滚只把 `ai.vector.mode` 切回 `baseline`。QV03 没有自动回滚删除逻辑，也不删除任一侧数据。

## Shadow 隔离与指标

- Qdrant Shadow 任务使用独立的有界 daemon executor 和 timeout scheduler；超时从 Qdrant 任务启动计时，不占用 baseline 的结果语义；
- 查询采样只控制 Shadow 查询；`shadow` 写入仍始终双写；
- 比较使用 `VectorMatch.id()` 逻辑 ID，Overlap@K 定义为 `交集逻辑 ID 数 / 请求 K`；
- `ShadowObservation` 和 `InMemoryShadowMetricsRecorder` 记录样本数、成功比较数、Overlap@K、两侧耗时和稳定错误码；
- 指标模型没有 vector、metadata、正文、scope 或认证字段；逻辑 ID 在遥测前去除控制字符、限制为 128 字符和最多 100 项；异常消息不会写入 Shadow 日志。

## 迁移与 checkpoint

生产入口为内部 `VectorMigrationService`，未新增公开 Controller：

- `MigrationOptions.defaults(...)` 固定默认 `dryRun=true`、批量 100；批量范围为 1-1000；
- dry-run 从空 cursor 分页读取全部源记录，校验分页和 Embedding，但不解析/写入 checkpoint、不调用目标 upsert 或目标计数；
- live 迁移只通过 `VectorRecordExporter.exportPage(VectorExportRequest)` 分页，且每个请求都显式携带受控 `assumedEmbedding`；返回记录的 Embedding 必须与该配置一致，不猜测或混合向量空间；
- 目标写入使用 `VectorBackend.upsertBatch`，依赖 QV02 的确定性 Point ID 和幂等 upsert；只有整批返回成功并且 checkpoint 原子保存成功后，内存中的成功 cursor 才推进；
- 批次部分写入、目标异常、checkpoint 保存失败或进程中断后，均从最后成功 checkpoint 重放同一批；测试替身验证部分写入后的重放不会增加逻辑记录数；
- 完成时通过内部 `MigrationTargetCounter` 适配点取得 Qdrant 精确数量，并比较源成功数；不一致以 `VECTOR_MIGRATION_FAILED` 显式失败；
- live 报告满足 `sourceRecords = successfulRecords + failedRecords`；完成报告要求 `failedRecords=0`。dry-run 另用 `plannedRecords` 表示计划写入数，不伪装为真实成功写入；
- 迁移从不删除 baseline 源数据。

checkpoint 默认位置为相对服务工作目录的：

```text
var/qdrant-migration/checkpoint.json
```

文件采用 JSON 格式，当前 `formatVersion=1`，字段为：

```text
formatVersion
migrationId
cursor
pagesCompleted
sourceRecords
successfulRecords
complete
assumedEmbedding { model, dimension, schemaVersion }
updatedAtEpochMillis
```

写入流程为同目录 `checkpoint.json.tmp` 写完后原子 rename；文件系统不支持原子 move 时退化为同目录 replace。checkpoint 只保存已确认成功的记录数，且恢复时强制校验 `migrationId` 与 `assumedEmbedding`，防止错误任务或不同向量空间续跑。

## 修改文件

路由生产文件：

- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/routing/DefaultVectorBackendRouter.java`；
- `ShadowRoutingOptions.java`、`ShadowOperation.java`、`ShadowObservation.java`；
- `ShadowMetricsRecorder.java`、`ShadowMetricsSnapshot.java`、`InMemoryShadowMetricsRecorder.java`。

迁移生产文件：

- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/migration/VectorMigrationService.java`；
- `MigrationOptions.java`、`MigrationStatus.java`、`MigrationReport.java`；
- `MigrationCheckpoint.java`、`MigrationCheckpointStore.java`、`FileMigrationCheckpointStore.java`；
- `MigrationTargetCounter.java`、`VectorMigrationException.java`。

测试与交接：

- `ai-service/src/test/java/com/javaee/aiservice/rag/vector/routing/DefaultVectorBackendRouterTest.java`；
- `ai-service/src/test/java/com/javaee/aiservice/rag/vector/migration/VectorMigrationServiceTest.java`；
- `docs/qdrant/handoffs/QV03-shadow-migration.md`。

未修改 `rag/vector/contract/**`、`rag/VectorStore.java`、任一底层后端、公开 Controller、`docs/PROGRESS.md`、任务看板或集成日志。

## 实际验证

### 编译

```powershell
.\mvnw.cmd -pl ai-service -am -DskipTests compile
```

结果：`BUILD SUCCESS`。

### QV03 专项

```powershell
.\mvnw.cmd -pl ai-service -am '-Dtest=DefaultVectorBackendRouterTest,VectorMigrationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：15/15 通过，Failures 0、Errors 0、Skipped 0。

覆盖 baseline 零 Qdrant 调用、Shadow 主结果对象不变、独立超时、0 采样、写入仍双写、Overlap@K、稳定错误、qdrant 禁止回退、默认值与校验、dry-run、分页、checkpoint、部分写入中断恢复、幂等重跑、Embedding 配置约束、最终数量不一致和原子 JSON checkpoint。

### QV03 + 冻结契约

```powershell
.\mvnw.cmd -pl ai-service -am '-Dtest=DefaultVectorBackendRouterTest,VectorMigrationServiceTest,VectorContractTest,VectorAcceptanceFixtureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：22/22 通过，Failures 0、Errors 0、Skipped 0。

### 完整 `ai-service`

```powershell
.\mvnw.cmd -pl ai-service -am test
```

结果：124 个测试，122 通过、Failures 0、Errors 0、2 个既有 QV01 HNSW 缺陷测试按 QV00 基线跳过，`BUILD SUCCESS`。运行使用非真实占位配置；外部模型认证失败，Redis/RabbitMQ 不可达，不代表这些外部服务可用。

### 交付门禁

```powershell
git diff --check
git diff --cached --check
```

结果：均通过；暂存变更 19 个文件，冻结契约和所有禁止文件命中 0。

## 待主集成任务完成

- 用 QV01 的 `LocalRedisHnswVectorBackend` Bean 构造 baseline，并让稳定 `VectorStore` 门面依赖本 router；
- 用惰性 `ObjectProvider`/Supplier 接入 QV02 的 Qdrant 后端，确认 `baseline` Spring 上下文不会提前创建 Qdrant Client；
- 把冻结配置 `ai.vector.mode`、`ai.vector.shadow.sample-rate` 和 `ai.vector.shadow.timeout-ms` 绑定到 router 构造参数；
- 由 QV02 提供按 `EmbeddingSpec` 精确计数的 `MigrationTargetCounter` 适配器；
- 用 QV01 的 `VectorRecordExporter` 验证真实 Redis 分页 cursor、旧记录 `assumedEmbedding` 应用与源数据快照/并发变更边界；
- 在 QV01/QV02/QV03 合并后执行真实双后端装配、Shadow 和迁移门禁；这些不在独立 QV03 分支伪造。

## 未验证与剩余风险

- 未启动或连接真实 Qdrant、Redis、Embedding、RabbitMQ 或完整服务，真实超时取消能否中止 Qdrant SDK 调用仍待 QV02 联调；
- Java `Future.cancel(true)` 只能发出中断信号；若底层 Qdrant SDK 不响应中断，用户主结果仍隔离，但后台调用可能持续到 QV02 自身 request timeout；
- 独立分支未验证 QV01 exporter 在迁移期间面对源数据并发写入时的快照一致性；正式迁移应在受控窗口执行并比较最终数量；
- checkpoint 是单进程内部任务的原子文件，不提供跨主机分布式锁；正式执行必须保证同一 checkpoint 只有一个迁移实例；
- 未验证真实 Qdrant 数量 API 是否为精确计数，集成适配器不得使用近似计数冒充最终核对；
- 回滚只验证了代码路径不删除数据，未执行真实 mode 切换或服务重启验收。
