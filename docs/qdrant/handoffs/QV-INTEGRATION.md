# Qdrant QV01～QV04 集成交接

## 结论

```text
AUTO_VERIFIED / MANUAL_PENDING
```

QV01～QV04 已按 `QV01 -> QV02 -> QV03 -> QV04` 合入 `docs/bootstrap-project-context`，协调集成提交为 `3457737`。代码自动验收已经完成，下一责任人是用户人工验收。

## 已交付

- Redis + JVM HNSW baseline 后端与稳定门面；
- Qdrant 官方 Java Client、Collection alias、payload index 和服务端 scope 过滤；
- `baseline`、`shadow`、`qdrant` 三模式装配；
- Shadow 对比、失败隔离和指标；
- 分页导出、dry-run、checkpoint、恢复、数量核对和幂等重跑迁移；
- RAG 查询与删除的 `userId + knowledgeBaseId` scope 修复；
- 真实双后端、双客户端、受控重启、迁移和清理验收入口。

## 自动证据

- QV04：159/159，Failures 0、Errors 0、Skipped 0；
- 完整 `ai-service`：178 个测试，Failures 0、Errors 0、Skipped 3；三个跳过项已由 QV04 专用入口执行；
- Recall@5：baseline 1.0，Qdrant 1.0；
- tenant leakage 0；delete residue 0；migration count difference 0；
- 敏感信息扫描：695 个 Git 可见文本文件，无命中；
- 验收资源清理：Qdrant 与 Redis DB 15 的 QV04 残留均为 0。

机器报告：

```text
ai-service/target/qv04-acceptance/qv04-run-report.json
```

报告是本机构建产物，重新清理或构建后可能消失；可用 `scripts/qdrant/run-qv04-acceptance.ps1 -Integrated` 重建。

## 配置与回滚

- 默认模式：`VECTOR_STORE_MODE=baseline`；
- Shadow 验证：`VECTOR_STORE_MODE=shadow`；
- Qdrant 切换：`VECTOR_STORE_MODE=qdrant`；
- 回滚：恢复 `VECTOR_STORE_MODE=baseline` 并重启服务；
- 迁移 checkpoint：`VECTOR_MIGRATION_CHECKPOINT_PATH`；
- 迁移不会自动运行，也不会删除 Redis 源数据。

## 人工验收入口

按 `docs/qdrant/06-HUMAN_ACCEPTANCE.md` 逐项确认：

1. baseline 无 Qdrant 依赖；
2. 多租户与跨知识库隔离；
3. Shadow 返回不变和 Qdrant 失败降级；
4. 迁移 dry-run、checkpoint 与重跑；
5. Qdrant 模式、双客户端与重启；
6. 删除、回滚和日志脱敏。

只有用户完成所有场景并签署后，才可把状态更新为 `HUMAN_ACCEPTED`。

## 未覆盖边界

- 真实远程 Embedding、真实模型和真实用户文档；
- 生产 TLS、API Key、网络策略和权限模型；
- 容量、成本、并发压力、长期稳定性、多节点 HA、备份和灾备。
