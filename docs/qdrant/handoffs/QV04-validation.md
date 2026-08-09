# QV04 任务书：独立自动验收与人工验收资产

## 初始状态

`TODO`

## 目标

建立不依赖真实用户数据和真实 Embedding 的独立验收资产，对本地后端与 Qdrant 执行相同契约、租户隔离、迁移、Shadow、重启和质量门禁，并形成可供用户执行的人工验收入口。

## 共同基线

从包含 `docs/qdrant/handoffs/QV00-contract-baseline.md` 的 QV00 交付提交创建。独立分支缺少 QV01-QV03 时必须报告 `PENDING_INTEGRATION`，不得把缺失实现计为通过。

## 允许修改

- 确定性向量、文档和查询 fixture；
- Qdrant 测试容器或受控本地测试服务；
- 双后端契约、租户、删除、重启、Shadow 和迁移测试；
- Recall@K、MRR、Overlap@K 和延迟报告；
- `scripts/qdrant/**`；
- `docs/qdrant/05-AUTO_TEST_REPORT.md`；
- `docs/qdrant/06-HUMAN_ACCEPTANCE.md`；
- 本交接文件。

## 禁止修改

- 生产实现和冻结契约；
- 放宽租户过滤、错误、超时或敏感信息规则；
- 使用真实用户材料；
- 提交真实 Qdrant、模型或基础设施密钥；
- 删除非测试前缀的 Collection；
- 把单机测试描述为生产容量证明。

## Fixture 最低要求

- 3 个用户；
- 每用户 2 个知识库；
- 每知识库至少 10 个分段；
- 存在跨用户语义高度相似的向量；
- 存在同文档多分段、无答案和删除场景；
- 向量确定性生成，不调用远程 Embedding。

## 硬门禁

```text
Qdrant Recall@5 >= baseline Recall@5
tenant leakage = 0
delete residue = 0
migration count difference = 0
```

还必须验证：

- 两个后端同一契约测试；
- 幂等 upsert；
- Qdrant 重启持久化；
- 两个独立客户端的一致可见性；
- baseline 不创建 Qdrant Client；
- qdrant 模式不扫描 Redis 重建索引；
- Shadow 主结果不变；
- 迁移 dry-run、checkpoint 和重跑；
- Agent、RAG、MCP 全量回归；
- 敏感信息和测试 Collection 边界检查。

## 性能报告

记录数据量、维度、P50/P95、写入吞吐、启动耗时和内存。第一阶段不要求 Qdrant 比 JVM HNSW 更快，但不得隐瞒测试机器、样本量和外部网络耗时。

## 交付要求

完成后：

- 独立分支阶段写 `HANDOFF_READY` 或 `PENDING_INTEGRATION`；
- 只有主协调任务集成 QV01-QV03 并执行完整门禁后才能写 `AUTO_VERIFIED`；
- 人工验收前保持 `MANUAL_PENDING`；
- 记录命令、测试计数、质量指标、环境、失败项和未验证内容。
