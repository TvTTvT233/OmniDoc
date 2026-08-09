# QV03 任务书：Shadow、迁移与切换

## 初始状态

`TODO`

## 目标

基于 QV00 契约实现 `baseline`、`shadow`、`qdrant` 三种路由，增加不改变用户结果的 Shadow 比较，以及从现有 Redis 向量到 Qdrant 的可恢复、幂等迁移。

## 共同基线

从包含 `docs/qdrant/handoffs/QV00-contract-baseline.md` 的 QV00 交付提交创建。任务可在冻结接口上独立实现，完整联调由主协调任务在 QV01/QV02 合入后执行。

## 允许修改

- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/routing/**`；
- `ai-service/src/main/java/com/javaee/aiservice/rag/vector/migration/**`；
- Shadow 指标、对比模型和内部执行脚本；
- 对应测试；
- 本交接文件。

## 禁止修改

- `rag/vector/contract/**`；
- `rag/VectorStore.java`；
- 本地 HNSW 和 Qdrant 底层实现；
- 新增公开迁移 Controller；
- 分段、Embedding、BM25、重排、Agent、MCP、前端和 `docs/PROGRESS.md`。

## 必须实现

1. `baseline` 只委托本地后端；
2. `shadow` 双写、双查但只返回 baseline；
3. Shadow 使用独立 timeout 和 0-1 sample rate；
4. Shadow 失败不影响 baseline，同时记录错误码；
5. `qdrant` 只返回 Qdrant 结果，自动验收时禁止静默回退；
6. 比较逻辑输出 Overlap@K、两侧耗时和逻辑 ID，不记录向量与正文；
7. 迁移默认 dry-run；
8. 使用 `VectorRecordExporter` 分页、checkpoint、批量和幂等 upsert；
9. 旧记录使用受控 `assumedEmbedding`，不得猜测；
10. 批次成功后才推进 checkpoint；
11. 支持失败批次重试和最终数量核对；
12. 回滚只切换 mode，不删除任一侧数据。

## 验收

- baseline 模式 Qdrant 完全不被调用；
- shadow 模式返回值与 baseline 完全一致；
- Qdrant timeout/异常不影响 shadow 主结果；
- qdrant 模式失败明确可见；
- dry-run 不写目标；
- 中断后从最后成功 checkpoint 继续；
- 重跑不增加 Point；
- `source = success + failed` 且完成时 `failed = 0`；
- 日志与指标无正文、向量和认证信息；
- `git diff --check` 通过。

## 交付要求

完成后把本文件改为 `HANDOFF_READY`，记录：

- 三模式实现和默认值；
- checkpoint 格式与存储位置；
- 迁移安全边界；
- 交付提交和修改文件；
- 实际测试和计数；
- 未完成的 QV01/QV02 联调项。
