# QV01 任务书：现有 Redis + JVM HNSW 后端适配

## 初始状态

`TODO`

## 目标

在不改变 `KnowledgeBase`、Agent RAG 和公开 API 的前提下，把现有 `VectorStore` 实现封装为 `LocalRedisHnswVectorBackend`，让 `VectorStore` 成为依赖冻结契约的稳定门面，并修复 QV00 发现的同 ID 覆盖与删除残留问题。

## 共同基线

从包含 `docs/qdrant/handoffs/QV00-contract-baseline.md` 的 QV00 交付提交创建。创建工作树时由主协调任务回填完整提交。

## 允许修改

- `ai-service/src/main/java/com/javaee/aiservice/rag/VectorStore.java`；
- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/local/**`；
- 本地后端 Spring 装配；
- `VectorStoreBaselineCharacterizationTest`；
- 新增本地后端契约测试；
- 本交接文件。

## 禁止修改

- `rag/vector/contract/**`；
- Qdrant SDK、Docker 和配置；
- Shadow、迁移和切换；
- `KnowledgeBase` 的分段、BM25、融合和重排；
- Agent、MCP、前端和 `docs/PROGRESS.md`。

## 必须实现

1. `LocalRedisHnswVectorBackend implements VectorBackend`；
2. baseline router 实现 `VectorBackendRouter`，mode 固定为 `BASELINE`；
3. `VectorStore` 保留现有对外方法并委托新后端，避免调用方同时重构；
4. 将 legacy metadata 中的保留字段转换为 `VectorScope`、`documentId` 和 `EmbeddingSpec`；
5. query/delete 强制租户作用域，新接口不得允许空作用域；
6. 相同逻辑 ID 重复 upsert 后只存在最新向量；
7. 删除覆盖过的 ID 后不再返回任何旧版本；
8. baseline 模式不创建 Qdrant Client；
9. 删除 `DocumentVectorizer` 中 Embedding 响应和向量维度的直接标准输出，改为不含向量内容的日志。

## 实现提示

当前 `hnswlib-core` 的版本语义需要显式处理。不要只调整测试预期；可以使用单调版本、重建单项索引或经测试证明可靠的替换策略，但必须保持并发安全和启动重建兼容。

## 验收

- 启用 `overwritingExistingItemMustReplaceItsVector`；
- 启用 `deletingOverwrittenItemMustNotLeaveAStaleIndexHit`；
- 本地后端通过冻结 `VectorBackend` 契约测试；
- 原 `KnowledgeBase`、`KnowledgeIndexAgent`、Agent RAG 测试通过；
- 默认配置启动时无 Qdrant 连接；
- 无完整向量和 Embedding 响应日志；
- `git diff --check` 通过。

## 交付要求

完成后把本文件改为 `HANDOFF_READY`，记录：

- 交付提交；
- 修改文件；
- 实际测试和计数；
- 覆盖/删除缺陷的根因与修复方式；
- 尚未验证内容和剩余风险。
