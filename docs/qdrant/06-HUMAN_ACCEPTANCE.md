# Qdrant 向量后端接入：人工验收

## 当前状态

`PENDING_IMPLEMENTATION`

QV00 仅完成契约和任务拆分。QV01-QV04 集成并达到 `AUTO_VERIFIED` 前，不执行本页正式验收。

## 前置条件

- 主协调任务已将 QV01-QV04 合入；
- 自动报告状态为 `AUTO_VERIFIED`；
- 使用测试用户和非敏感文档；
- Qdrant 只暴露在受控本地或内部网络；
- API Key 通过环境变量输入，不粘贴到聊天、命令历史、URL 或仓库文件；
- 已记录当前 `VECTOR_STORE_MODE`，便于回滚。

## 验收场景

### 1. Baseline

1. 设置 `VECTOR_STORE_MODE=baseline`；
2. 启动 AI 服务但不启动 Qdrant；
3. 索引测试文档并完成检索；
4. 确认不会创建 Qdrant Client 或访问 Qdrant。

### 2. 多租户数据准备

1. 用户 A 在 `kb-a` 索引两份文档；
2. 用户 B 索引语义高度相似的文档；
3. 用户 A 再创建 `kb-b` 并索引另一份文档；
4. 记录各文档和分段数量。

### 3. Shadow

1. 设置 `VECTOR_STORE_MODE=shadow`；
2. 重复相同查询；
3. 确认用户结果仍来自 baseline；
4. 查看 baseline/Qdrant TopK、Overlap@K、耗时和错误；
5. 暂停 Qdrant，确认 baseline 查询仍成功且 Shadow 错误可见。

### 4. 迁移

1. 先执行 dry-run；
2. 核对预计记录数、Embedding 规格和目标 Collection；
3. 执行正式迁移；
4. 中断一次后从 checkpoint 继续；
5. 重复执行迁移，确认 Point 数不增长；
6. 核对源数量、成功数、失败数和目标数量。

### 5. Qdrant 正式查询

1. 设置 `VECTOR_STORE_MODE=qdrant`；
2. 用户 A 查询，只能返回用户 A 当前知识库内容；
3. 切换知识库，确认没有跨知识库结果；
4. 用户 B 查询，确认没有用户 A 结果；
5. 重启 AI 服务，确认不扫描 Redis 重建向量索引；
6. 使用第二个 AI 客户端确认新增 Point 立即可见。

### 6. 删除和回滚

1. 删除一份文档；
2. 确认正文、分段和 Qdrant Point 均无残留；
3. 停止 Qdrant，确认 Qdrant 模式明确报错且不伪装成功；
4. 切回 baseline；
5. 确认原有检索能力恢复；
6. 不删除 Qdrant 或 Redis 数据，保留审计证据。

## 通过标准

- 自动门禁全部通过；
- 人工场景无跨租户和跨知识库数据；
- Shadow 不改变用户结果；
- 迁移可恢复、幂等且数量一致；
- Qdrant 模式重启后可用；
- 删除无残留；
- 回滚成功；
- 日志无向量、正文和认证信息；
- 未把单机结果描述为生产可用。

## 验收结论

人工验收完成前保持：

```text
MANUAL_PENDING
```

只有用户确认全部场景后才能改为：

```text
HUMAN_ACCEPTED
```
