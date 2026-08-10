# Qdrant 向量后端接入：人工验收

## 当前状态

```text
MANUAL_PENDING
```

QV01～QV04 已集成，自动报告已经达到 `AUTO_VERIFIED`，现在可以开始人工验收。自动通过不替代用户对模式切换、隔离、降级、迁移、重启和回滚体验的最终确认；在用户签署前状态保持 `MANUAL_PENDING`。

当前自动证据：集成提交 `3457737`，QV04 专用入口 159/159、0 skipped，四项硬门禁全部通过。自动报告为本地构建产物 `ai-service/target/qv04-acceptance/qv04-run-report.json`。

## 前置条件

- 主协调任务已按 QV01、QV02、QV03、QV04 顺序集成；
- `run-qv04-acceptance.cmd -Integrated` 成功，机器报告状态为 `AUTO_VERIFIED`；
- 四项硬门禁均有真实数值且通过；
- Qdrant 为受控本地服务或测试容器，不连接未经授权的外部实例；
- 只使用 `qv04-vector-fixture.json` 或用户另行创建的非敏感合成数据；
- Qdrant Key 仅通过环境变量传入，不写入仓库、URL、聊天、截图或报告；
- 测试 Collection 和 alias 均以 `qv04_test_` 开头；
- 已记录 `ai.vector.mode`、Collection alias 和回滚值。

## 自动报告复核

打开 `ai-service/target/qv04-acceptance/qv04-run-report.json`，逐项确认：

| 检查 | 必须满足 |
| --- | --- |
| 状态 | `AUTO_VERIFIED` |
| fixture | 3 用户 × 每用户 2 知识库 × 每库 10 段，共 60 段 |
| 远程 Embedding | `false` |
| Recall@5 | Qdrant 不低于 baseline |
| 租户泄漏 | 0 |
| 删除残留 | 0 |
| 迁移数量差 | 0 |
| 跳过测试 | 0，或每项有主协调任务批准的书面解释 |
| 环境 | 机器、Java、数据量、维度和外部网络边界完整 |
| 性能 | 两后端 P50/P95、吞吐、内存和 Qdrant 重启耗时均有数值 |

若任一值为 `null`、缺失或只来自 `HARNESS_SELF_TEST_ONLY`，停止验收并退回自动验证。

## 验收场景

### 1. Baseline 无 Qdrant 依赖

1. 设置 `ai.vector.mode=baseline`；
2. 不启动 Qdrant，启动 AI 服务；
3. 使用合成用户 A 的两个知识库完成索引与查询；
4. 确认结果只来自本地后端；
5. 确认启动和查询日志没有创建或连接 Qdrant Client；
6. 确认日志没有完整向量、正文、Key 或认证 Header。

通过证据：成功查询、backend/mode 记录、Qdrant Client 创建计数为 0。

### 2. 多租户和跨知识库隔离

1. 导入 60 段 QV04 合成 fixture；
2. 对 3 个用户的 6 个 scope 分别查询 `shared-eligibility-evidence`；
3. 该语义在不同用户和知识库中拥有相同向量，核对 TopK 仍只属于当前 `userId + knowledgeBaseId`；
4. 执行带 `documentId` 的查询，确认结果只来自目标文档；
5. 记录任何未知逻辑 ID；出现一个即失败。

通过证据：tenant leakage = 0，跨知识库 leakage = 0，document leakage = 0。

### 3. Shadow 主结果不变

1. 设置 `ai.vector.mode=shadow` 和采样率 1.0；
2. 重复相同 fixture 查询；
3. 保存 baseline、用户实际返回和 Qdrant 的逻辑 ID 顺序；
4. 确认用户实际返回与 baseline 完全一致；
5. 记录 Overlap@K、两侧耗时、样本数和错误码；
6. 暂停 Qdrant 或注入明确的 Qdrant 失败；
7. 再次查询，确认 baseline 仍成功、用户结果不变且 Shadow 错误可见。

通过证据：正常和失败注入时 `returnedIds == baselineIds`，日志无向量、正文或认证信息。

### 4. 迁移 dry-run、checkpoint 和重跑

1. 先执行 dry-run，核对源数量 60、Embedding 规格和目标 `qv04_test_` Collection；
2. 确认 dry-run 目标写入数为 0；
3. 正式迁移并在一个批次前注入失败；
4. 确认失败批次前后 checkpoint 不前移；
5. 恢复后继续，确认 checkpoint 前移且最终失败数为 0；
6. 核对 `source = success + failed`、`source = target`；
7. 重跑相同迁移，确认目标 Point 数不增长；
8. 确认源 Redis 数据未删除，`assumedEmbedding` 已明确报告。

通过证据：dry-run writes = 0、migration count difference = 0、重跑差 = 0、sourceDeleted = false。

### 5. Qdrant 正式模式、两客户端和重启

1. 设置 `ai.vector.mode=qdrant`；
2. 确认 router 类型为 `QDRANT`；
3. 确认 AI 服务启动不扫描 Redis 重建本地 HNSW；
4. 客户端一写入合成 probe，客户端二立即查询；
5. 重启 Qdrant 服务，等待健康检查通过；
6. 使用重启后的新 Client 查询 probe；
7. 记录重启耗时、查询 P50/P95、吞吐和内存；
8. 停止 Qdrant，确认 qdrant 模式明确失败且不静默回退。

通过证据：两客户端可见、重启后可见、redisIndexScans = 0、故障错误码符合冻结契约。

### 6. 删除与回滚

1. 按逻辑 ID 删除 fixture 的 `delete-record`；
2. 按文档删除 fixture 的 `delete-document`，期望删除 5 段；
3. 分别用 scope 查询和 document filter 查询，确认无残留；
4. 将模式切回 baseline；
5. 确认 baseline 检索恢复；
6. 不自动删除 Redis 源数据；
7. 测试 Collection 只有在名称再次通过 `qv04_test_` 前缀检查后才可清理，禁止清理稳定 alias 或非测试 Collection。

通过证据：delete residue = 0，回滚查询成功，未发生非测试 Collection 删除。

## 人工记录

| 项目 | 记录 |
| --- | --- |
| 日期与执行人 | 待填写 |
| 集成提交 | `3457737b7b36c3eb5f5e15357e6ab44dc94d34f1` |
| 机器与 Java | Windows 10.0.19045；OpenJDK 17.0.19；其余见自动报告 |
| Qdrant/Client 版本 | Qdrant `v1.18.3-unprivileged`；Java Client 版本见 `ai-service/pom.xml` |
| 测试 Collection | 自动生成 `qv04_test_20260810t005900578z`，验收后已清理 |
| 自动报告路径与状态 | `ai-service/target/qv04-acceptance/qv04-run-report.json`；`AUTO_VERIFIED` |
| Baseline | 通过 / 失败 / 未执行 |
| 多租户隔离 | 通过 / 失败 / 未执行 |
| Shadow | 通过 / 失败 / 未执行 |
| 迁移 | 通过 / 失败 / 未执行 |
| 两客户端与重启 | 通过 / 失败 / 未执行 |
| 删除与回滚 | 通过 / 失败 / 未执行 |
| 异常、截图和日志位置 | 待填写；不得包含密钥、正文或完整向量 |

## 结论规则

- 任何场景失败、未执行、证据缺失或出现跨租户结果：保持 `MANUAL_PENDING`；
- 自动报告不是 `AUTO_VERIFIED`：保持 `MANUAL_PENDING`；
- 只有用户确认所有场景和证据后，主协调任务才能把结论改为：

```text
HUMAN_ACCEPTED
```

单节点、本机和合成数据验收通过仍不等于生产容量、生产高可用或真实用户数据安全评估完成。
