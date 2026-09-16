# Qdrant 向量后端接入：自动测试报告

## 结论

```text
AUTO_VERIFIED / MANUAL_PENDING
```

QV01～QV04 已接入主运行边界，QV04 专用入口已对真实 Redis + JVM HNSW 和本地 Qdrant 执行完整门禁。机器报告位于：

```text
ai-service/target/qv04-acceptance/qv04-run-report.json
```

该报告属于构建产物，不提交 Git；重新执行验收会覆盖它。

## 执行版本与入口

- 分支：`docs/bootstrap-project-context`；
- 被验收提交：`3457737b7b36c3eb5f5e15357e6ab44dc94d34f1`；
- 日期：2026-08-10；
- Qdrant：`qdrant/qdrant:v1.18.3-unprivileged`，本地单节点容器；
- Redis：本地容器，验收数据隔离在 DB 15；
- 远程网络、远程 Embedding、真实用户数据：未包含。

完整门禁：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\qdrant\run-qv04-acceptance.ps1 `
  -Integrated `
  -ProviderClass com.javaee.aiservice.rag.vector.qdrant.Qv04ProductionAcceptanceProvider
```

## 测试结果

| 测试组 | 运行 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| QV04 资产、Agent/RAG/MCP 回归 | 110 | 0 | 0 | 0 |
| 真实双后端、重启、Shadow、迁移 | 49 | 0 | 0 | 0 |
| 合计 | 159 | 0 | 0 | 0 |

静态敏感信息检查扫描 695 个 Git 可见文本文件，无密钥形态命中。测试 Collection 与 alias 均使用本次生成的 `qv04_test_` 前缀。

## 确定性 fixture

| 项目 | 数值或边界 |
| --- | --- |
| 用户 | 3 |
| 每用户知识库 | 2 |
| 每知识库分段 | 10 |
| 总分段 | 60 |
| 文档 | 每知识库 2 个，每文档 5 段 |
| 向量维度 | 16 |
| 生成算法 | 固定 seed + SHA-256 + 归一化 |
| 远程 Embedding | 禁止 |
| 跨租户高相似 | 6 个 scope 共享语义键并产生完全相同向量 |

## 硬门禁

| 门禁 | 实际结果 | 结论 |
| --- | ---: | --- |
| `Qdrant Recall@5 >= baseline Recall@5` | `1.0 >= 1.0` | 通过 |
| tenant leakage | `0` | 通过 |
| delete residue | `0` | 通过 |
| migration count difference | `0` | 通过 |

两套后端 MRR 均为 1.0；无答案 fixture 各返回了 5 个 ANN 候选。无答案场景没有统一阈值契约，因此只记录候选数，不将“必须为空”作为底层门禁。

## 性能快照

| 指标 | Redis + JVM HNSW | Qdrant |
| --- | ---: | ---: |
| Recall@5 | 1.0 | 1.0 |
| 查询 P50 | 31,014 μs | 5,234 μs |
| 查询 P95 | 59,944 μs | 5,514 μs |
| 幂等写入吞吐 | 134.58 records/s | 731.42 records/s |
| JVM 内存增量 | 1,820,688 bytes | 12,055,456 bytes |

这是 60 条、16 维、8 个查询的单次本机合成快照。它可作为本项目回归证据，不能推导生产容量、成本或 Qdrant 一定比基线更快。

## 行为门禁

- `baseline`：不创建 Qdrant Client；
- `qdrant`：router 类型为 Qdrant，不扫描 Redis 构造本地 HNSW；
- 双客户端：客户端一写入后客户端二可见；
- 持久性：真实执行 `docker compose restart qdrant`，新 Client 可见重启前数据；
- Shadow 正常路径：用户返回严格等于 baseline，Overlap@K 为 1.0；
- Shadow 失败路径：注入 Qdrant 失败后 baseline 结果仍正常返回，错误被记录；
- 删除：按逻辑 ID 和按文档删除均无残留；
- scope：高相似跨租户数据没有越过 `userId + knowledgeBaseId`；
- 清理：验收结束后 Qdrant `qv04_test_*` 资源残留为 0，Redis DB 15 中 `vector:qv04-*` 与 `metadata:qv04-*` 残留均为 0。

## 迁移门禁

| 项目 | 结果 |
| --- | ---: |
| dry-run 目标写入 | 0 |
| 源记录 | 60 |
| 成功 | 60 |
| 失败 | 0 |
| 目标记录 | 60 |
| 幂等重跑后目标记录 | 60 |
| 失败批次 checkpoint 前移 | 否 |
| 恢复后 checkpoint 前移 | 是 |
| assumed Embedding 已报告 | 是 |
| Redis 源数据被删除 | 否 |

## 完整项目回归

在同一协调代码上执行 `ai-service` 完整测试：

```text
Tests run: 178
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
```

三个跳过项在普通回归中有意隔离真实基础设施：QV04 integrated gate、Qdrant backend integration 和 Qdrant persistence；它们已在上面的专用入口中全部执行通过，专用入口为 0 skipped。

完整回归使用非真实占位模型配置，四个远程模型请求均因无有效凭据返回认证失败，实际模型成功 0/4。这一结果只证明本次代码回归通过，不证明模型服务可用。

## 仍未验证

- 用户人工验收，状态保持 `MANUAL_PENDING`；
- 真实远程 Embedding 与真实模型驱动的 RAG 端到端链路；
- 真实用户数据和生产权限模型；
- 生产 TLS、API Key、网络隔离、限流、容量、成本、HA、备份和灾备；
- 多节点 Qdrant、长期稳定性和并发压力。

下一步按 `06-HUMAN_ACCEPTANCE.md` 执行人工验收。只有用户签署后才能改为 `HUMAN_ACCEPTED`。
