# Qdrant 向量后端接入：任务看板

## 状态定义

- `TODO`：尚未开始；
- `IN_PROGRESS`：正在进行；
- `BLOCKED`：存在明确阻塞；
- `HANDOFF_READY`：专项任务已自测并交接；
- `MERGED`：主协调任务已集成；
- `AUTO_VERIFIED`：自动验收通过；
- `HUMAN_ACCEPTED`：用户人工验收通过。

## 当前看板

| 编号 | 任务 | 建议分支 | 状态 | 依赖 |
| --- | --- | --- | --- | --- |
| QV00 | 契约、基线与任务拆分 | `docs/bootstrap-project-context` | `HANDOFF_READY` | 无 |
| QV01 | 现有 Redis + JVM HNSW 后端适配 | `feature/qdrant-local-baseline` | `TODO` | QV00 交付提交 |
| QV02 | Qdrant 后端与基础设施 | `feature/qdrant-vector-backend` | `TODO` | QV00 交付提交 |
| QV03 | Shadow、迁移与切换 | `feature/qdrant-shadow-migration` | `TODO` | QV00 交付提交；集成时需要 QV01/QV02 |
| QV04 | 独立自动验收与人工验收资产 | `test/qdrant-vector-acceptance` | `TODO` | QV00 交付提交；完整门禁需要 QV01-QV03 已集成 |

## Codex 任务与工作树

QV01-QV04 尚未创建。创建后由主协调任务填写任务 ID、工作树和共同基线提交。

| 编号 | Codex 任务 ID | 工作树 |
| --- | --- | --- |
| QV01 | `PENDING` | `../.codex-worktrees/qdrant-qv01` |
| QV02 | `PENDING` | `../.codex-worktrees/qdrant-qv02` |
| QV03 | `PENDING` | `../.codex-worktrees/qdrant-qv03` |
| QV04 | `PENDING` | `../.codex-worktrees/qdrant-qv04` |

## 共同规则

- 所有任务从同一个 QV00 提交创建，不从彼此分支派生；
- 不修改 `rag.vector.contract` 冻结类型；
- 每个任务只修改分配给自己的生产文件、测试和 handoff；
- 子任务不修改 `docs/PROGRESS.md`、本看板或主集成日志，避免冲突；
- 发现公共契约缺口时记录复现和建议，交给主协调任务；
- 任务结束必须形成一个可审查提交和 handoff；
- 未运行的真实 Qdrant、Embedding 或完整服务不能写成已验证。

## QV01 文件所有权

允许：

- 现有 `rag/VectorStore.java`；
- 新增 `rag/vector/local/**`；
- 本地后端 Spring 装配；
- `VectorStoreBaselineCharacterizationTest` 及本地契约测试；
- `docs/qdrant/handoffs/QV01-local-baseline.md`。

必须完成：

- `VectorStore` 成为稳定门面；
- 当前实现封装为 `LocalRedisHnswVectorBackend`；
- baseline 模式不创建 Qdrant Client；
- 修复“同一 ID 覆盖未替换旧向量、后续删除仍返回旧 HNSW 命中”的已知缺陷，并启用两项回归测试；
- 删除 Embedding 响应和向量的直接标准输出。

禁止：

- Qdrant SDK、Docker、迁移和 Shadow；
- BM25、分段、模型和 RAG Prompt 修改。

## QV02 文件所有权

允许：

- Qdrant Maven 依赖；
- `rag/vector/qdrant/**`；
- Qdrant properties、client、Collection、alias、payload index；
- `docker-compose.local.yml` 中 Qdrant 服务和测试数据卷；
- `.env.example` 中无秘密的变量名；
- Qdrant 后端测试；
- `docs/qdrant/handoffs/QV02-qdrant-backend.md`。

禁止：

- 修改本地 HNSW 实现；
- 修改 `KnowledgeBase` 的 BM25、重排和结果融合；
- Shadow 和迁移服务。

## QV03 文件所有权

允许：

- `rag/vector/routing/**`；
- `rag/vector/migration/**`；
- Shadow 对比、切换、迁移、checkpoint 和指标；
- 对应测试和内部脚本；
- `docs/qdrant/handoffs/QV03-shadow-migration.md`。

禁止：

- 修改两套底层后端实现；
- 新增未授权的公开管理 Controller；
- 修改 Agent、MCP、分段、BM25、重排和前端。

## QV04 文件所有权

允许：

- 确定性 Qdrant fixture；
- `VectorBackend` 双实现契约测试；
- 多租户、迁移、重启和 Shadow 验收脚本；
- 质量评测数据、报告和人工验收文档；
- `docs/qdrant/handoffs/QV04-validation.md`。

禁止：

- 修改生产实现；
- 放宽租户过滤、错误或敏感信息规则以通过测试；
- 使用真实用户文档或提交真实密钥。

## 集成顺序

1. QV01 本地后端适配；
2. QV02 Qdrant 后端；
3. QV03 Shadow 与迁移；
4. QV04 验收资产；
5. 主协调任务补齐跨任务装配并运行统一门禁；
6. 用户执行人工验收。
