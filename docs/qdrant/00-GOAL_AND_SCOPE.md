# Qdrant 向量后端接入：目标与范围

## 背景

OmniDoc 当前使用 Redis 持久化向量与元数据，并在每个 AI 服务 JVM 内维护 HNSW 索引。该实现可以作为单机、小规模 RAG 的学习和回归基线，但存在多实例索引不一致、启动扫描重建、召回后过滤和向量版本管理不足等边界。

本阶段不以删除现有实现为目标，而是引入 Qdrant 作为可切换后端，并通过相同数据、相同查询和相同评测规则比较两套实现。

## 目标

1. 保留当前 Redis + JVM HNSW 实现作为 `baseline`；
2. 建立供应商无关的向量后端契约；
3. 接入单节点 Qdrant，支持持久化、幂等写入、Cosine 检索和服务端元数据过滤；
4. 支持 `baseline`、`shadow`、`qdrant` 三种运行模式；
5. 支持已有 Redis 向量向 Qdrant 的可恢复、幂等迁移；
6. 用确定性 fixture 和离线标注集比较正确性、隔离性和检索质量；
7. 保留一键回滚到基线的能力。

## 成功标准

- Qdrant 与本地实现通过同一套 `VectorBackend` 契约测试；
- 所有查询强制携带 `userId + knowledgeBaseId`，跨租户结果为零；
- Qdrant `Recall@5` 不低于基线；
- Shadow 模式只记录对比，不改变用户收到的基线结果；
- 迁移任务支持 dry-run、分页、checkpoint 和幂等重跑；
- Qdrant 模式下 AI 服务启动不扫描 Redis 重建 Qdrant 索引；
- Qdrant 故障、超时、维度不匹配和配置错误具有稳定错误语义；
- 原有 Agent、RAG、MCP 能力无回归。

## 本期包含

- Dense Vector；
- 当前 DashScope Embedding 模型和维度；
- Cosine 相似度；
- Qdrant 单节点 Docker 部署；
- payload index 与强制多租户过滤；
- 双后端切换、Shadow 对比、迁移与回滚；
- Collection 版本、alias、健康检查和最小可观测性；
- 自动测试、质量报告和人工验收。

## 本期明确不包含

- 不升级 Spring Boot、Spring AI 或现有模型栈；
- 不更换 Embedding 模型和向量维度；
- 不重写分段、BM25、重排、RAG Prompt；
- 不引入稀疏向量、知识图谱或多模态向量；
- 不实现 Qdrant 分布式集群、Qdrant Cloud 或生产高可用；
- 不修改 MCP、Agent Memory、文件流程或前端；
- 不自动删除原 Redis 向量；
- 不使用真实用户材料执行自动验收；
- 不把单机结果描述为生产容量证明。

## 安全原则

- 租户作用域在 Java 契约和数据库过滤两层强制执行；
- Qdrant Point ID 不直接使用可冲突的业务 ID；
- API Key 只从环境变量读取，不写入仓库、URL 或日志；
- 日志和 Trace 不记录完整向量、文档正文或认证信息；
- 自动迁移不是公开用户 API，只允许受控的内部管理入口；
- 自动验收不得连接未明确授权的外部 Qdrant 或真实 Embedding 服务。
