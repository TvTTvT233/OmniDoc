# QV02 交接：Qdrant 后端与基础设施

## 状态

`HANDOFF_READY`

已基于 QV00 冻结契约完成独立 `QdrantVectorBackend`、官方 Java Client 生命周期、Collection/alias/index 管理和本地单节点 Qdrant。未修改冻结契约、本地 HNSW、Shadow、迁移或 RAG/Agent 业务逻辑。

## 基线与交付

- 分支：`feature/qdrant-vector-backend`；
- 起始 HEAD：`3ec7a0fb629aac3d526ac03058b577b71f921a2b`；
- 起始工作区：干净；
- 交付提交：本 handoff 所在的 QV02 单一提交，最终哈希以任务交付报告为准；
- Qdrant Java Client：`io.qdrant:client:1.18.3`；
- 本地镜像：`qdrant/qdrant:v1.18.3-unprivileged`；
- 本次实测镜像摘要：`sha256:affb67e1d6f2f93d7d20b90d238a7d4b974d36351c162e73bda794e4b2e03483`；
- 实测 Qdrant Server：`1.18.3`。

## 已完成

- `QdrantConfiguration` 只在 `shadow`/`qdrant` 模式创建 Client；`baseline` 和缺省模式均不创建 Qdrant Client；
- 官方 gRPC Client 使用配置的连接探测超时和请求超时，Bean 销毁时关闭 Client/channel；启动时显式健康检查，不依赖 Client 构造期的无界版本探测；
- `UuidV5VectorPointIdFactory` 使用 SHA-1、RFC variant 和 version 5 位实现标准 UUIDv5；固定 OmniDoc namespace 为 `9e20ca0c-77c0-5539-b61e-a7f5d07b5725`，未使用 `UUID.nameUUIDFromBytes`；
- Point name 严格为 `userId + "\n" + knowledgeBaseId + "\n" + record.id`，任一 scope 字段变化都会改变 Point ID；
- 真实 Collection 按 `omnidoc_chunks_<model>_<dimension>_v<schemaVersion>` 生成，异常模型字符带哈希后缀避免规范化碰撞；
- 稳定 alias 默认为 `omnidoc_chunks_active`；启动时只在 alias 缺失时创建，不会静默改指已有 alias；alias 指向其他 Collection、维度不符、非单一 unnamed dense vector 或非 Cosine 时均以 `VECTOR_CONFIGURATION_ERROR` 失败；
- Collection 固定使用 Cosine 和配置维度；建立并复查以下 payload index：`userId`/Keyword、`knowledgeBaseId`/Keyword、`documentId`/Keyword、`embeddingModel`/Keyword、`schemaVersion`/Integer；
- search 使用 Qdrant 通用 Query API，请求内强制 `userId AND knowledgeBaseId`，按需追加 `documentId` 和受支持 attributes；不执行无租户 ANN 后的 Java 过滤；
- 单条/批量 upsert 使用确定性 Point ID，批内同 ID 去重并按配置批大小提交；按 ID 删除使用 `tenant filter AND has_id`，按文档删除使用 `tenant filter AND documentId`；
- payload 只保存保留字段和 metadata，不复制正文；查询结果移除保留字段后映射回统一 `VectorMatch`；
- 认证、超时、不可用、配置、维度及 upsert/search/delete 失败映射到冻结 `VectorErrorCode`，消息不包含 API Key、向量或正文；
- 本地 Compose 只把 HTTP/gRPC 绑定到 `127.0.0.1`，使用命名数据卷，并通过容器内 Bash TCP 请求检查 `/healthz`；
- 集成测试清理函数硬性拒绝删除非 `test_` 前缀 alias/Collection。

## Collection、alias 与 payload index

- 生产 Collection 前缀：`omnidoc_chunks_`；
- 默认 alias：`omnidoc_chunks_active`；
- Distance：`Cosine`；
- Vector：单一 unnamed dense vector，维度由 `ai.vector.dimension` 固定；
- payload index：`userId`、`knowledgeBaseId`、`documentId`、`embeddingModel` 为 Keyword，`schemaVersion` 为 Integer；
- 保留 payload：`id`、`userId`、`knowledgeBaseId`、`documentId`、`embeddingModel`、`embeddingDimension`、`schemaVersion`。

## 修改文件

- `.env.example`；
- `ai-service/pom.xml`；
- `docker-compose.local.yml`；
- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/qdrant/**`；
- `ai-service/src/test/java/com/javaee/aiservice/rag/vector/qdrant/**`；
- `docs/qdrant/handoffs/QV02-qdrant-backend.md`。

未修改 `rag/vector/contract/**`、`rag/VectorStore.java`、`docs/PROGRESS.md`、任务看板、集成日志或其他 QV 工作树。

## 验证结果

### 确定性与完整回归

- `./mvnw.cmd -pl ai-service -am -DskipTests compile`：通过；
- `./mvnw.cmd -pl ai-service -am '-Dtest=Qdrant*Test,UuidV5VectorPointIdFactoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：22 个测试，20 通过、0 失败、0 错误、2 个需显式开启真实 Qdrant 的测试跳过；
- 完整 `ai-service`（全部所需变量使用非真实占位值）：131 个测试，127 通过、0 失败、0 错误、4 跳过，`BUILD SUCCESS`；4 个跳过项为 2 个 QV00 已登记 HNSW 缺陷测试和 2 个显式真实 Qdrant 集成入口；
- 完整回归中的 4 个远程模型实际成功仍为 0/4，不能证明真实模型可用；
- `./mvnw.cmd -pl ai-service -am dependency:tree -Dincludes=io.qdrant:client,io.grpc:*,com.google.protobuf:*`：解析 `io.qdrant:client:1.18.3`、gRPC `1.82.0`、protobuf-java `3.25.8`；
- `docker compose -f docker-compose.local.yml config --quiet`：通过；
- Compose `/healthz` healthcheck：首次启动和重启后均为 `healthy`；
- `git diff --check`：通过。

### 真实 Qdrant fixture

- `QdrantVectorBackendIntegrationTest`：1/1 通过；静态 3 维向量 fixture 覆盖 3 个用户 × 2 个知识库共 6 个 Point；
- 重复 upsert 后精确 Point 数保持 6；逐一查询 6 个 scope，只返回本 scope 逻辑 ID；删除文档返回 1，随后受 scope+document 约束的查询无残留；健康检查通过；
- `QdrantPersistenceIntegrationTest` 的 `write`、`read`、`cleanup` 三阶段各 1/1 通过；在 write/read 之间执行 `docker compose restart qdrant` 并等待 healthcheck，重启后仍检索到原 Point；
- 清理后实测 Qdrant Collection 数为 0，没有残留 `test_` Collection；清理逻辑没有也不能删除非测试前缀资源；
- 验证完成后 Qdrant 容器已停止，命名卷保留，未执行 `down -v` 或删除数据卷。

## 尚未验证与剩余风险

- 未使用真实 Embedding；所有向量均为确定性静态 fixture；
- 未使用真实 Qdrant API Key 启动认证实例；认证错误映射通过确定性 gRPC status 单测验证，真实认证部署仍待集成/人工验收；
- 冻结配置没有 TLS 开关，本地 Client 使用内网 plaintext gRPC；Compose 端口只绑定 loopback，但生产跨主机部署必须另行确认 TLS/网络隔离方案；
- 未启动完整 Redis、RabbitMQ、MinIO、MySQL 与 `ai-service` 的外部基础设施闭环，未验证 QV01/QV03 集成、真实 RAG 路由、Shadow 或迁移；
- 完整回归使用非真实占位配置，真实模型调用 0/4；
- `deleteByDocument` 为精确 count 后执行等待完成的 filter delete；并发写入同一文档时返回计数只能代表删除前快照，跨请求并发一致性仍需压力测试；
- 未执行大规模数据、长时间稳定性、并发启动竞争或性能基准；多实例同时创建 Collection/index/alias 的 `ALREADY_EXISTS` 竞争已容错，但仍需目标环境压力验证。

## 集成提示

主协调任务按冻结顺序先集成 QV01，再集成本提交。QV03 可注入命名 Bean `qdrantVectorBackend`；alias 切换应由 QV03 的受控迁移流程完成，QV02 启动逻辑不会自动重指一个已存在的 alias。
