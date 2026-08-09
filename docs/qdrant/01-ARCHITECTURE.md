# Qdrant 向量后端接入：架构

## 当前基线

```text
KnowledgeBase
  -> DocumentVectorizer
  -> VectorStore
       -> Redis vector:* / metadata:*
       -> JVM HNSW
  -> 自定义 BM25
  -> Reranker
```

当前 HNSW 只存在于本地 JVM，启动时扫描 Redis 重建；过滤在 ANN 候选返回后执行。此行为保留为对比基线，不再作为 Qdrant 的实现方式。

## 目标结构

```text
KnowledgeBase / KnowledgeIndexAgent
              |
       VectorStore facade
              |
     VectorBackendRouter
       /        |        \
 baseline    shadow     qdrant
    |           |          |
 Local       Local +     Qdrant
 Redis       Qdrant      primary
 HNSW        compare
```

### `baseline`

- 写入和查询只使用 `LocalRedisHnswVectorBackend`；
- 不创建 Qdrant Client；
- 用于回归、离线对照和紧急回滚。

### `shadow`

- 写入同时发送给本地后端和 Qdrant；
- 查询以本地后端为主结果；
- Qdrant 查询受独立超时和采样率约束；
- 对比 TopK、Overlap@K、耗时和错误，不改变用户响应；
- Qdrant 失败不得阻断基线，但必须形成可观察的失败记录。

### `qdrant`

- Qdrant 是正式写入和查询后端；
- 自动验收时禁止静默降级到基线；
- 可通过配置显式双写基线，但查询结果只能来自 Qdrant；
- 回滚通过切换 `ai.vector.mode=baseline` 完成，不删除任何一侧数据。

## 数据模型

真实 Collection 名称包含向量空间版本：

```text
omnidoc_chunks_<embedding-model>_<dimension>_v<schema-version>
```

稳定别名：

```text
omnidoc_chunks_active
```

Point：

- ID：由 `userId + knowledgeBaseId + logicalId` 生成的确定性 UUIDv5；
- Vector：当前 Dense Embedding；
- Payload：`segmentId`、`documentId`、`userId`、`knowledgeBaseId`、分段信息和向量空间版本；
- 正文仍以现有 Redis 内容存储为准，本阶段不复制完整正文到 Qdrant。

必须预先建立 payload index：

- `userId`；
- `knowledgeBaseId`；
- `documentId`；
- `embeddingModel`；
- `schemaVersion`。

## 一致性边界

Redis 正文和 Qdrant Point 不使用分布式事务。本阶段采用：

1. 确定性 Point ID；
2. 幂等 upsert；
3. 索引任务失败状态；
4. 批量重试和文档级清理；
5. 迁移数量核对；
6. 删除残留验收。

不允许把部分写入伪装成完整成功。

## 配置契约

配置前缀固定为 `ai.vector`：

```yaml
ai:
  vector:
    mode: baseline
    dimension: 1536
    embedding:
      model: text-embedding-v1
      schema-version: 1
    qdrant:
      host: qdrant
      grpc-port: 6334
      api-key: ${QDRANT_API_KEY:}
      collection-alias: omnidoc_chunks_active
      connect-timeout-ms: 3000
      request-timeout-ms: 5000
      batch-size: 100
    shadow:
      sample-rate: 1.0
      timeout-ms: 3000
    migration:
      batch-size: 100
```

## 可观测性

至少记录：

- backend、mode、collection alias；
- topK、过滤字段名、查询耗时；
- Shadow baseline/Qdrant 耗时与 Overlap@K；
- upsert、delete、migration 成功和失败数量；
- 稳定错误码；
- 健康状态。

禁止记录完整查询向量、原始正文、Qdrant API Key 和未经脱敏的用户数据。
