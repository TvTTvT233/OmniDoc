# QV01 交接：现有 Redis + JVM HNSW 后端适配

## 状态

`HANDOFF_READY`

现有 Redis 持久化与 JVM HNSW 实现已经封装为 `LocalRedisHnswVectorBackend`，`VectorStore` 已成为依赖冻结 `VectorBackendRouter` 契约的稳定门面。旧 RAG 调用方法保留，新强类型写入、查询和删除方法通过冻结契约执行。

## 基线与交付提交

- 分支：`feature/qdrant-local-baseline`；
- 起始 HEAD：`3ec7a0fb629aac3d526ac03058b577b71f921a2b`；
- 起始工作区：干净；
- 交付提交：本交接文件所在的 QV01 单一提交，最终哈希见任务交付报告。

## 完成内容

- 新增 `LocalRedisHnswVectorBackend implements VectorBackend`，保留 `vector:*`、`metadata:*` Redis 数据与 JVM HNSW 启动重建路径；
- 新增固定返回 `BASELINE` / `LOCAL_REDIS_HNSW` 的 `BaselineVectorBackendRouter`，仅在 `ai.vector.mode=baseline` 或配置缺省时装配；
- `VectorStore` 保留原 `store/search/delete` 方法，并新增对冻结 `VectorRecord`、`VectorQuery`、`VectorScope` 等类型的直接委托；
- legacy metadata 的 `userId`、`knowledgeBaseId`、`documentId`、`embeddingModel`、`embeddingDimension`、`schemaVersion` 已转换为强类型契约字段，Redis metadata 写回时保留这些字段供重启重建；
- 新强类型查询、ID 删除与文档删除均要求非空租户作用域，租户和文档过滤在返回结果前强制执行；
- 原两项禁用覆盖/删除回归已启用；新增本地后端测试覆盖租户/文档隔离、稳定错误码、启动重建和历史节点容量回收；
- `DocumentVectorizer` 不再把完整 Embedding 响应或向量信息写入标准输出，仅保留不含向量内容的维度 debug 日志；
- 未引入 Qdrant SDK、Docker、Shadow 或迁移实现，未修改冻结契约包。

## 覆盖与删除缺陷

### 根因

原 HNSW 构建器没有调用 `withRemoveEnabled()`。`hnswlib-core 1.1.2` 在该默认模式下对 `remove(id, version)` 直接返回 `false`，因此覆盖前的删除没有发生；同 ID 新向量也无法可靠替换旧节点，后续删除仍会留下旧命中。原索引项还一直使用 `Item.version()` 的默认值 `0`，没有显式版本顺序。

### 修复

- HNSW 构建显式启用删除；
- 每次写入和删除使用进程内单调递增版本；
- Redis 写入、HNSW 替换、查询和删除由同一读写锁保护；
- 覆盖造成的已删除历史节点累计到 HNSW 容量上限时，从 Redis 当前有效记录重建索引并继续本次写入；
- 删除成功后从 HNSW 活跃集合移除；最后一项删除后释放空索引，避免保留旧向量空间；
- 启动重建测试、同 ID 覆盖测试、覆盖后删除测试和低容量重复覆盖测试均通过。

## 修改文件

- `ai-service/src/main/java/com/javaee/aiservice/rag/VectorStore.java`；
- `ai-service/src/main/java/com/javaee/aiservice/rag/DocumentVectorizer.java`；
- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/local/BaselineVectorBackendRouter.java`；
- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/local/LegacyVectorOperations.java`；
- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/local/LocalRedisHnswVectorBackend.java`；
- `ai-service/src/test/java/com/javaee/aiservice/rag/VectorStoreBaselineCharacterizationTest.java`；
- `ai-service/src/test/java/com/javaee/aiservice/rag/vector/local/LocalRedisHnswVectorBackendTest.java`；
- `docs/qdrant/handoffs/QV01-local-baseline.md`。

## 实际验证

### QV01 专项与冻结契约

```text
.\mvnw.cmd -pl ai-service -am '-Dtest=VectorStoreBaselineCharacterizationTest,LocalRedisHnswVectorBackendTest,VectorContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 16
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

### 旧 Agent / RAG 直接回归

```text
.\mvnw.cmd -pl ai-service -am '-Dtest=KnowledgeIndexAgentTest,AgentExecutionServiceTest,AgentControllerTest,VectorStoreBaselineCharacterizationTest,LocalRedisHnswVectorBackendTest,VectorContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 54
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

该命令执行后新增了 1 项 HNSW 低容量重建专项测试；最终代码状态由后续 16 项专项和 116 项完整回归覆盖。

### 默认 Spring 上下文

使用明确的非真实占位配置执行：

```text
.\mvnw.cmd -pl ai-service -am '-Dtest=AllModelsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 1
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

默认模式成功创建 `BaselineVectorBackendRouter`，代码与依赖静态检查没有 Qdrant Client 实现或 SDK。测试环境未连接 Redis/RabbitMQ；四个远程模型调用均因非真实占位凭据认证失败，实际模型成功为 `0/4`。

### 完整 `ai-service` 回归

使用同一组非真实占位配置执行：

```text
.\mvnw.cmd -pl ai-service -am test
Tests run: 116
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

首次完整回归只提供模型占位配置时为 115 项中 114 项通过、1 项 Spring 上下文错误，原因是既有 `MINIO_ACCESS_KEY` 未设置；补齐全部非真实占位配置后最终完整回归 116/116 通过。

### 静态检查

- `DocumentVectorizer` 中 `System.out` / `System.err`、完整 Embedding 响应输出：无命中；
- QV01 本地后端、`VectorStore` 和 `ai-service/pom.xml` 中 Qdrant SDK / Client：无命中；
- 两项已知缺陷测试的 `@Disabled`：无命中；
- `git diff --check`：通过。

## 未验证与剩余风险

- 未连接真实 Redis，Redis 持久化、真实启动灌入、网络失败恢复和大数据量扫描未做外部联调；单元测试使用模拟 Redis 与真实 JVM HNSW；
- 未调用真实 Embedding，未验证真实模型维度与 `ai.vector.embedding` 配置的一致性；
- 未引入或运行真实 Qdrant，未验证 Collection、alias、payload filter、Qdrant Client 或网络；
- 未运行 Docker、Shadow、迁移、切换、回滚或完整外部基础设施端到端服务；
- 为兼容当前 `KnowledgeIndexAgent` 等旧调用方，缺少完整 `userId + knowledgeBaseId` 的旧 `search/delete` 方法仍通过 baseline 专用兼容桥执行；所有新增强类型接口都强制租户作用域。后续切换到 Shadow/Qdrant 时，主集成任务需迁移这些旧调用方或为新 router 提供等价兼容桥；
- 本地 HNSW 仍采用 ANN 后 Redis metadata 过滤。为保证租户过滤后的 TopK 完整性，当前查询候选覆盖全部活跃 HNSW 项；数据量增大时的内存、延迟和吞吐尚未压测。
