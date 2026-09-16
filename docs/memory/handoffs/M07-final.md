# Memory V1 M07 最终自动验收门禁交接

## 1. 最终状态

```text
AUTO_VERIFIED / MANUAL_PENDING / PENDING_EXTERNAL
```

- 独立门禁任务：`019ff12e-5e68-7660-b231-136ae00cca2b`；
- 独立交付提交：`d6f7b05286d4a9dc88a90d3e7e5424987b8651d3`；
- 主分支合入提交：`2248d0e`；
- 主协调补齐 M06-C readiness 与完整前端门禁：`40ef1cf`；
- 验收分支：`docs/bootstrap-project-context`；
- 首次完整门禁 HEAD：`40ef1cfbccceff92e894d2660889a09bd86a0c5d`；最终复跑 HEAD 以机器报告中的 `environment.head` 为准；
- 机器报告：`ai-service/target/memory-v1-acceptance/memory-v1-report.json`。

该状态表示冻结的本地确定性门禁已全部通过，不表示 `HUMAN_ACCEPTED`，也不表示真实 MySQL、Redis、模型或浏览器已经验证。

## 2. 主任务集成复核

独立任务交付后，主任务没有直接接受旧基线结论，而是重新复核并完成两项集成修正：

- readiness 识别已经合入的 `useConversationWorkspace`、活动/归档列表、分页、真实新建、归档/恢复/删除与 `clientRequestId`，不再把 M06-C 误报为缺失；
- M07 前端选择从旧的 M06-A 两文件 14 项扩展为三组精确门禁：契约 25 项、翻转基线 6 项、独立 UI 验收 11 项，共 42 项；另运行异步任务测试和生产构建。

门禁继续保持以下严格边界：

- Java 测试类和 M02-M05 冻结目标逐名列出，不使用 `*Memory*Test`；
- 不设置 `surefire.failIfNoSpecifiedTests=false`；
- 运行前清理旧 Surefire XML，并核对 suite/testcase/failure/error/skipped；
- `-Integrated` 前后都要求 Git 工作区干净；
- 报告通过 JSON Schema 校验；
- 删除残留、跨用户访问、摘要 coverage、CAS、token 预算和七条模型调用路径都有结构化机器证据；
- 未执行的外部依赖必须留在 `unverified`，不能由本地替身冒充。

## 3. 实际自动验收结果

执行：

```powershell
.\scripts\memory\run-m07-acceptance.cmd -Integrated
```

结果：

| 分组 | 数量 | 结果 |
| --- | ---: | --- |
| M07 资产自检 | 16 | 通过 |
| 精确 Java 集成门禁 | 129 | 通过 |
| M06 前端确定性门禁 | 42 | 通过 |
| 总计 | 187 | Failures 0 / Errors 0 / Skipped 0 |
| 异步任务轮询（不计入 187） | 2 | 通过 |
| Web 生产构建（不计入 187） | 1674 modules | 通过，保留既有大 chunk 警告 |

机器指标：

- message loss 0；duplicate sequence 0；
- coverage gap 0；coverage duplicate 0；stale summary overwrite 0；
- unsupported confirmed items 0；successful cross-user access 0；
- token over-budget 0；模型调用路径 7/7；
- Conversation、Message、Summary、Redis fixture key、Runtime State fixture key 删除残留均为 0；
- Memory Embedding 调用 0；Memory Qdrant Collection 0。

外围回归另行执行，不被重复计入 Memory M07 的 187 项：

- MCP 搜索集成选择 80/80 通过，并完成静态泄漏检查；
- Qdrant 本地确定性资产/Agent/RAG/MCP 选择 112/112 通过；真实 Qdrant 集成因本机 `localhost:6334` 未启动而保持 `PENDING_EXTERNAL`，没有伪装成通过；
- 完整 `ai-service` 除真实模型探测外执行 291 项，Failures 0、Errors 0、Skipped 3；三个 skipped 均为需要显式启用真实 Qdrant 的集成门禁；
- `AllModelsTest` 单独完成 1/1 应用上下文启动验证。该测试不把占位 Key 的远程调用失败作为模型可用性通过，真实模型仍未验证。

完整应用上下文首轮还发现 `JdbcConversationSummaryStore` 被声明为 `final`，Spring 无法为数据库异常翻译创建代理。主任务移除该错误限制后，应用上下文和完整本地回归均通过。

## 4. 尚未验证的外部边界

机器报告保留：

```text
PENDING_EXTERNAL:REAL_BROWSER_JWT
PENDING_EXTERNAL:REAL_MYSQL
PENDING_EXTERNAL:REAL_REDIS
PENDING_EXTERNAL:REAL_MODEL
PENDING_EXTERNAL:REAL_QDRANT
PENDING_EXTERNAL:REMOTE_EMBEDDING
```

其中真实 Qdrant 和远程 Embedding 不是 Memory V1 的运行依赖；它们被保留是为了证明现有 RAG/Qdrant 能力没有被 Memory 冒用。当前删除残留 0 来自 H2/MySQL-mode 与受控 Runtime/Redis fixture，不是外部基础设施清理证据。

## 5. 已知风险与人工验收重点

- 异步 Agent 提交使用稳定 `clientRequestId`，但页面刷新后没有持久化原 `jobId` 来恢复轮询；人工验收需覆盖“提交成功后断线/刷新”的行为，当前不能宣称该场景已解决；
- 真实浏览器要验证 Global Assistant 与 Editor 的刷新恢复、长历史分页、归档、恢复、永久删除和双用户 JWT 隔离；
- 真实 MySQL 要验证服务重启、并发 sequence 与级联删除；真实 Redis 要验证 TTL 和故障降级；
- 真实模型要重点验收事实修正、建议与事实分离、长对话滚动摘要准确性；
- 前端构建仍有既有的单 chunk 超过 500 kB 警告，不属于本轮 Memory 正确性阻塞。

人工验收清单见 `docs/memory/04-HUMAN_ACCEPTANCE.md`。用户完成并记录全部场景前，状态保持 `MANUAL_PENDING`。
