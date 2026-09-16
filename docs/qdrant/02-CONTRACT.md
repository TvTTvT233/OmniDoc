# Qdrant 向量后端接入：冻结契约

## 冻结规则

本文档和 `com.javaee.aiservice.rag.vector.contract` 是 QV01-QV04 的共同边界。专项任务不得自行改变类型名称、配置名称、模式语义、租户过滤、Point ID 规则或错误语义；确需修改时停止开发并交由主协调任务决策。

## Java 包

```text
com.javaee.aiservice.rag.vector.contract
```

核心类型：

- `VectorBackend`：统一写入、查询、删除和健康检查；
- `VectorBackendRouter`：供稳定 `VectorStore` 门面依赖的运行时路由；
- `VectorRecord`：待写入的供应商无关向量记录；
- `VectorQuery`：包含 TopK、Embedding 规格和强制过滤的查询；
- `VectorMatch`：统一结果，分数越高越相关；
- `VectorScope`：强制 `userId + knowledgeBaseId`；
- `VectorFilter`：可选文档过滤和扩展属性；
- `EmbeddingSpec`：模型、维度和 Schema 版本；
- `VectorRecordExporter`：仅供内部迁移分页导出基线；
- `VectorBackendException`：稳定后端错误。

## 不变量

- 每个写入、查询和删除必须携带非空 `VectorScope`；
- 任意 metadata/attributes 不得覆盖 `userId`、`knowledgeBaseId`、`documentId` 等保留字段；
- Vector 长度必须等于 `EmbeddingSpec.dimension`；
- Vector 和 Map 在契约边界执行防御性复制；
- `topK` 范围为 1-100，外部 Controller 可以进一步限制；
- Qdrant 和本地后端统一返回“分数越高越相关”；
- 批量写入必须保持幂等，不允许通过重复 Point 表示版本；
- `VectorRecordExporter` 不得暴露为公开 Controller。

## 模式语义

| 配置值 | 语义 | Qdrant 失败行为 |
| --- | --- | --- |
| `baseline` | 现有后端读写 | 与请求无关，不创建 Client |
| `shadow` | 双写、双查、返回基线 | 记录失败，不影响基线结果 |
| `qdrant` | Qdrant 正式读写 | 明确失败，不静默回退 |

`VectorBackendRouter.type()` 返回当前用户结果所属的有效后端：`baseline` 和 `shadow` 返回 `LOCAL_REDIS_HNSW`，`qdrant` 返回 `QDRANT`。即使 `qdrant` 模式显式双写 baseline，也不得把 router 类型报告为本地后端。

## 后端命名

- `local-redis-hnsw`；
- `qdrant`。

## Point ID

QV02 必须实现标准 UUIDv5：

```text
namespace = OmniDoc 固定 namespace UUID
name = userId + "\n" + knowledgeBaseId + "\n" + record.id
```

规则：

- 相同输入必须生成相同 ID；
- 任一租户字段不同必须生成不同 ID；
- 日志以逻辑 ID 为主，不要求暴露完整 Point ID；
- 不得使用 Java `UUID.nameUUIDFromBytes` 冒充 UUIDv5，因为该方法生成 UUIDv3。

## Collection

- Collection 的维度和距离在创建时固定；
- 距离固定为 Cosine；
- 真实集合使用模型、维度和 Schema 版本命名；
- 应用只查询稳定 alias；
- alias 指向不兼容维度时启动检查必须失败；
- 自动测试不自动删除非测试前缀的 Collection。

## 过滤契约

Qdrant 查询必须在数据库请求中包含：

```text
userId == scope.userId
AND knowledgeBaseId == scope.knowledgeBaseId
```

`documentId` 不为空时再增加：

```text
AND documentId == filter.documentId
```

不得先执行无租户 ANN 查询后在 Java 内过滤。

## 迁移契约

- 迁移默认 dry-run；
- 从 `VectorRecordExporter` 分页读取；
- 旧数据缺少 Embedding 版本时必须由受控配置提供 `assumedEmbedding` 并在报告中明确；
- 每批写入成功后才能更新 checkpoint；
- 重跑相同批次不得增加记录；
- 完成后比较源记录数、成功数、失败数和 Qdrant 数量；
- 自动迁移不得删除源数据。

## Shadow 契约

- 用户结果始终来自 baseline；
- Qdrant 查询拥有独立超时；
- 采样率范围 0-1；
- 比较逻辑按逻辑 record ID，不按 Qdrant Point ID；
- 至少产生 Overlap@K、两侧耗时、错误码和样本数量；
- 不记录向量和正文。

## 错误语义

| 错误码 | 语义 | 建议重试 |
| --- | --- | --- |
| `VECTOR_BACKEND_DISABLED` | 所选后端未启用 | 否 |
| `VECTOR_BACKEND_UNAVAILABLE` | 后端不可连接或不健康 | 是 |
| `VECTOR_AUTH_FAILED` | Qdrant 认证失败 | 否 |
| `VECTOR_TIMEOUT` | 连接或请求超时 | 是 |
| `VECTOR_CONFIGURATION_ERROR` | Collection、alias 或配置错误 | 否 |
| `VECTOR_DIMENSION_MISMATCH` | 查询/写入向量维度不兼容 | 否 |
| `VECTOR_FILTER_REQUIRED` | 缺少租户过滤 | 否 |
| `VECTOR_UPSERT_FAILED` | 写入失败 | 视后端原因 |
| `VECTOR_SEARCH_FAILED` | 查询失败 | 视后端原因 |
| `VECTOR_DELETE_FAILED` | 删除失败 | 视后端原因 |
| `VECTOR_MIGRATION_FAILED` | 迁移批次失败 | 修复后从 checkpoint 重试 |

## HTTP 边界

- QV01-QV03 不新增公开的任意向量写入或导出接口；
- 现有 RAG/Agent API 保持兼容；
- 管理型迁移入口如确有需要，必须由主协调任务单独确认鉴权和审计方案；
- 自动验收优先通过内部 Service 或脚本执行迁移。
