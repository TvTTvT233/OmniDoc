# QV02 任务书：Qdrant 后端与基础设施

## 初始状态

`TODO`

## 目标

基于 QV00 冻结契约实现独立 `QdrantVectorBackend`，加入本地单节点 Docker 服务、Collection/alias 管理、payload index 和官方 Java Client；不得修改现有本地 HNSW 实现。

## 共同基线

从包含 `docs/qdrant/handoffs/QV00-contract-baseline.md` 的 QV00 交付提交创建。创建工作树时由主协调任务回填完整提交。

## 允许修改

- `ai-service/pom.xml` 中 Qdrant 官方 Java Client 依赖；
- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/qdrant/**`；
- Qdrant properties、client、Collection、alias 和 payload index 实现；
- Qdrant 后端单元与集成测试；
- `docker-compose.local.yml` 的 Qdrant 服务与数据卷；
- `.env.example` 的无秘密变量名；
- 本交接文件。

## 禁止修改

- `rag/vector/contract/**`；
- `rag/VectorStore.java` 和本地 HNSW；
- Shadow、迁移和切换；
- `KnowledgeBase`、分段、BM25、重排、Agent、MCP、前端和 `docs/PROGRESS.md`。

## 必须实现

1. 官方 Java Client，连接、请求和关闭生命周期明确；
2. `QdrantVectorBackend implements VectorBackend`；
3. 标准 UUIDv5 `VectorPointIdFactory`，不得使用 UUIDv3 代替；
4. Collection 名称由模型、维度、Schema 版本生成；
5. 稳定 alias `omnidoc_chunks_active`；
6. Cosine、固定维度和 alias 兼容性检查；
7. `userId`、`knowledgeBaseId`、`documentId`、`embeddingModel`、`schemaVersion` payload index；
8. search 在 Qdrant 请求中强制多租户过滤；
9. 幂等单条/批量 upsert、按 ID 删除、按文档删除；
10. 健康检查和稳定错误映射；
11. Qdrant 默认不对外网暴露，认证信息不进入日志。

## 验收

- 相同 record 重复 upsert 后 Point 数不变；
- 任一 scope 字段不同会生成不同 Point ID；
- 三用户、两知识库 fixture 无跨租户结果；
- 删除文档无残留；
- Qdrant 重启后数据保留；
- 维度、alias、认证、超时和不可用错误语义正确；
- Qdrant 后端通过冻结契约测试；
- Docker healthcheck 可用；
- 不依赖真实 Embedding；
- `git diff --check` 通过。

## 交付要求

完成后把本文件改为 `HANDOFF_READY`，记录：

- SDK 与镜像版本；
- Collection、alias 和 payload index；
- 交付提交和修改文件；
- 实际测试、fixture 和计数；
- 尚未验证内容及部署风险。
