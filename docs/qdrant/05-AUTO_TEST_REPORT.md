# Qdrant 向量后端接入：自动测试报告

## QV00 自动验证范围

QV00 只验证供应商无关契约和当前本地实现的确定性特征，不连接真实 Redis、Qdrant 或 Embedding 服务。

### 契约测试

`VectorContractTest` 覆盖：

- Vector 和 metadata 防御性复制；
- 强制租户作用域；
- Embedding 维度一致性；
- 保留字段不可被任意 metadata 覆盖；
- 批量 upsert 默认顺序；
- 冻结配置值；
- 迁移分页 cursor 约束。

### 基线特征测试

`VectorStoreBaselineCharacterizationTest` 使用模拟 Redis 和真实本地 HNSW，覆盖：

- 确定性向量写入与 Cosine TopK；
- `userId + knowledgeBaseId` 的现有召回后过滤行为；
- 首次写入后的确定性查询。

覆盖替换和删除残留两项测试当前禁用，原因见集成日志。QV01 修复后必须全部启用。

## QV01-QV04 最终门禁

最终自动验收至少包含：

1. 两个后端运行同一套契约测试；
2. 三用户、每用户两知识库、每库不少于十段的隔离 fixture；
3. Qdrant 幂等 upsert、文档删除、重启持久化；
4. Shadow 主结果不变、错误隔离和 Overlap@K；
5. 迁移 dry-run、checkpoint、中断继续和数量核对；
6. `baseline` 模式无 Qdrant 依赖；
7. `qdrant` 模式无 Redis HNSW 重建；
8. 原 Agent、RAG、MCP 全量回归；
9. 敏感信息和测试 Collection 边界检查。

## 质量门禁

```text
Qdrant Recall@5 >= baseline Recall@5
tenant leakage = 0
delete residue = 0
migration count difference = 0
```

Overlap@K、P50/P95、内存和吞吐必须记录，但第一阶段不要求 Qdrant 比本地 JVM HNSW 更快。

## QV00 实际结果

### QV00 专项

```text
Tests run: 10
Passed: 8
Failures: 0
Errors: 0
Skipped: 2
```

两项跳过测试分别对应：

- 相同 ID 覆盖写入必须替换旧向量；
- 覆盖写入后的删除不得留下旧 HNSW 命中。

### 完整 `ai-service` 回归

```text
Tests run: 109
Passed: 107
Failures: 0
Errors: 0
Skipped: 2
BUILD SUCCESS
```

完整回归使用进程内非真实占位配置。四个远程模型实际成功仍为 `0/4`，缺少 Redis/RabbitMQ 外部基础设施的日志不影响测试终态；该结果不能证明真实模型、Redis、RabbitMQ 或 Qdrant 可用。

QV00 没有 Qdrant 生产 Bean，完整 Spring 上下文没有创建或连接 Qdrant Client。本文档不得提前把 Qdrant 能力标记为通过。

交付前 `git diff --cached --check` 通过；静态敏感信息检查扫描 621 个文本文件，无命中。
