# Qdrant 向量后端接入：集成日志

## 当前状态

```text
AUTO_VERIFIED / MANUAL_PENDING
```

QV01～QV04 已按冻结顺序合入 `docs/bootstrap-project-context`。协调提交 `3457737` 补齐跨任务生产装配、迁移源导出、RAG scope 删除/检索和真实 QV04 验收适配器。自动门禁已通过，用户人工验收尚未执行。

## 基线与集成顺序

- QV00 共同基线：`3ec7a0fb629aac3d526ac03058b577b71f921a2b`；
- 集成顺序：`QV01 -> QV02 -> QV03 -> QV04 -> coordinator`；
- 四个来源提交均为单一可审查提交，交接状态均为 `HANDOFF_READY`；
- 集成前已核对文件所有权，未修改冻结的 `rag/vector/contract/**`；
- 四次 cherry-pick 均无冲突。

| 编号 | 来源分支 | 来源提交 | 主分支提交 | 集成后专项结果 |
| --- | --- | --- | --- | --- |
| QV01 | `feature/qdrant-local-baseline` | `93fb4f8` | `f99d04a` | 16/16 |
| QV02 | `feature/qdrant-vector-backend` | `6e62e0d` | `d9f2efb` | 20/22；2 项真实 Qdrant 测试按设计在专项阶段跳过 |
| QV03 | `feature/qdrant-shadow-migration` | `3eb4efc` | `b2c73d0` | 22/22 |
| QV04 | `test/qdrant-vector-acceptance` | `695ee73` | `6e71324` | 验收资产合入无冲突 |
| 协调集成 | `docs/bootstrap-project-context` | — | `3457737` | 统一自动验收 159/159 |

## 协调集成内容

- `baseline` 只构造 Redis + JVM HNSW；`shadow` 同步返回 baseline 并异步对比 Qdrant；`qdrant` 不构造或扫描本地 HNSW；
- Spring 生产装配使用供应商无关的 `VectorBackend` 边界，不向业务层泄漏 Qdrant Client；
- 本地后端实现稳定逻辑 ID 游标分页导出，保留 scope、documentId 和 Embedding 规格，用作可恢复迁移源；
- Qdrant 后端实现活动 alias 的迁移数量核对；迁移只在 `shadow` 模式提供内部 Bean，不自动执行、不新增公开 Controller；
- 知识库检索在进入向量后端前传入 `userId + knowledgeBaseId`，文档删除改为一次带 scope 的 `deleteByDocument`；
- 新增真实 QV04 provider：隔离 Redis DB 15、两个独立 Qdrant Client、受控 Qdrant 重启、Shadow 失败注入、迁移 checkpoint/恢复/重跑和测试资源清理；
- 验收脚本在集成模式显式执行真实 Qdrant 与持久性测试，普通全量回归仍隔离外部基础设施测试。

## 最终自动验证

### QV04 集成验收

执行版本：`3457737b7b36c3eb5f5e15357e6ab44dc94d34f1`

```text
资产与 Agent/RAG/MCP 回归：110/110
真实双后端集成：49/49
合计：159/159
Failures: 0
Errors: 0
Skipped: 0
状态：AUTO_VERIFIED
人工状态：MANUAL_PENDING
```

硬门禁：Qdrant Recall@5 1.0，baseline Recall@5 1.0；tenant leakage 0；delete residue 0；migration count difference 0。敏感信息检查扫描 695 个 Git 可见文本文件，无命中。

验收结束后确认不存在 `qv04_test_*` Qdrant Collection/alias，Redis DB 15 中不存在 `vector:qv04-*` 或 `metadata:qv04-*` 残留。

### 完整 `ai-service` 回归

```text
Tests run: 178
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
```

三个跳过项均为普通回归中有意隔离的真实基础设施门禁：QV04 integrated gate、Qdrant 后端集成测试和 Qdrant 持久性测试；三项均已在 QV04 专用集成入口中执行通过。完整回归使用非真实模型占位配置，四个远程模型实际成功仍为 0/4。

## 决策与剩余边界

- 自动结果允许进入用户人工验收，不等于已经 `HUMAN_ACCEPTED`；
- 测试使用确定性 16 维合成向量，未调用真实远程 Embedding，未使用真实用户文档；
- 本机单节点的延迟、吞吐和内存数据只用于回归对比，不是生产容量、TLS、鉴权、HA 或灾备证据；
- 尚未验证真实模型驱动的完整 RAG 端到端链路；
- Qdrant 迁移没有公开管理 API，也不会随服务启动自动迁移或删除 Redis 源数据；
- 回滚值保持为 `ai.vector.mode=baseline`。
