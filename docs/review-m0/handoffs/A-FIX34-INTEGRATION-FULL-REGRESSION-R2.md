# Review M0 A-FIX3/A-FIX4 修复集成及全量自动回归 R2

## 1. 判定与边界

- 判定：`FIX_INTEGRATION_R2_READY`。
- 本任务只完成 A-FIX3/A-FIX4 集成及同一集成代码树的全量自动回归，不是完整 R4-A。
- 授权下一任务以本分支最终唯一 HEAD 作为输入执行“完整重跑 A”；不授权跳过 A，也未启动 B/C/D。
- 未移动 `milestone/review-m0`，未修改主 checkout，未合并主分支，未开发 UI/P-08/P-09，未调用远程模型或远程 MCP。
- P-08 保持 `LOCAL_ONLY`；P-09 保持 `NOT_RUN / PENDING`；本判定不表示 `HUMAN_ACCEPTED`。

集成生产代码与测试的已验证 SHA 为 `56b7a788a04bdec04f9aa22864d76fe2c0578a76`。本文和 `docs/PROGRESS.md` 是其后的纯文档交付提交；Git 提交不能在自身内容中稳定自引用自身 SHA，最终结果 SHA 由交付回执和 `git rev-parse HEAD` 给出。

## 2. 固定输入、隔离与 ancestry

| 项目 | 实际值 |
| --- | --- |
| Git 根 | `C:\Users\lab\Desktop\docAI-main\OmniDoc` |
| 固定基线 | `cb1ded9b9dfcf762e28a1c1064bd58f85e40b957` |
| 分支 | `integration/review-m0-a-fixes-r2` |
| worktree | `C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-a-fix-integration-r2` |
| A-FIX3 实现 | `4dea8e63150a0bcc7a8849fd6585fec5f86f9d7a` |
| A-FIX3 handoff | `e46813bac4c644e60241a269ccb9447ce473fa75` |
| A-FIX4 实现 | `7e325051fd1fc6c4671a53d16fdc124aa69cbaba` |
| A-FIX4 handoff | `40cce021f2180d00b9e9ff83d76f9d362a4facbc` |

已逐项核对：基线是两个实现提交的祖先，A-FIX3 实现是其 handoff 的祖先，A-FIX4 实现是其 handoff 的祖先；基线、A-FIX3 和 A-FIX4 输入 worktree 均清洁。本 worktree 从固定基线新建；没有覆盖、删除或修改其他 worktree、主 checkout 或 milestone。

## 3. 集成顺序与冲突

| 顺序 | 来源 | 集成提交 | 结果 |
| ---: | --- | --- | --- |
| 1 | A-FIX3 实现 `4dea8e6` | `a9466b7` | 保留来源提交 |
| 2 | A-FIX3 handoff `e46813b` | `69988e8` | 保留来源提交 |
| 3 | A-FIX4 实现 `7e32505` | `ca3ad7d` | 保留来源提交；唯一文件冲突待独立语义提交 |
| 4 | A-FIX4 handoff `40cce02` | `a704f3e` | 保留来源提交 |
| 5 | 语义冲突解决 | `56b7a788a04bdec04f9aa22864d76fe2c0578a76` | 独立提交 |

唯一文件级冲突为 `ai-service/src/test/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiIdempotencySnapshotTest.java`。独立冲突提交完整保留了两侧语义：

- A-FIX3：瞬态失败结束整个失败事务，下一次重试使用新的完整 `REQUIRES_NEW` 事务；失败尝试不留下 claim、业务写或快照。
- A-FIX4：只把真实锁竞争归入可重试；连接获取/存储不可用不进入 contention 重试；持久竞争受总预算约束；耗尽后使用稳定 storage-unavailable envelope。

合并后该测试类 13/13 通过；没有删除测试、降低断言、改变 skip 或恢复 rollback-only store 内重试。生产调用仍由完整事务重试入口拥有；旧真实 MySQL 门仍通过正式事务所有者执行。连接不可用仍快速映射 HTTP 503 / `REVIEW_M0_STORAGE_UNAVAILABLE`，服务端 LOCAL_ONLY 上限保持 15 秒。

## 4. FIX3/FIX4 组合门

计数均为 `discovered / passed / failures / errors / skipped`。

| 门 | 结果 |
| --- | --- |
| A-FIX1/A-FIX3/A-FIX4 合并精确选择器 | `63 / 63 / 0 / 0 / 0` |
| A-FIX2 安全日志/API 合同 | `40 / 40 / 0 / 0 / 0` |
| 旧真实 MySQL 8 runner，第 1 轮 | `2 / 2 / 0 / 0 / 0` |
| 旧真实 MySQL 8 runner，第 2 轮 | `2 / 2 / 0 / 0 / 0` |
| 旧真实 MySQL 8 runner，第 3 轮 | `2 / 2 / 0 / 0 / 0` |
| concurrent idempotency runner seed | `1 / 1 / 0 / 0 / 0` |
| concurrent idempotency runner restart verify | `1 / 1 / 0 / 0 / 0` |
| 原生 file/ai fat Jar package | 4 个 reactor 模块成功 |

旧 runner 的三轮是三个独立连续执行，不使用整门自动重跑掩盖失败。新 runner 继续通过双实例、8/16/48 同键并发、失败事务回滚接管、异载荷 409、owner 隔离和 MySQL 重启重放；V001-V003、长 scope/摘要碰撞、CAS、取消恢复、lease/checkpoint/双 dispatcher、报告不可变/OUTDATED/回滚等由组合门和后续 Review 门共同覆盖。

## 5. 原生 fat Jar + MySQL 8 HTTP 门

使用本次源码生成的原生 ai-service fat Jar、真实 MySQL 8、生产 JWT filter 和 `LOCAL_ONLY` 配置执行。Review schema 数量为 3，最高版本为 3，即 V001-V003。

| 并发度 | HTTP 201 | HTTP 500 | HTTP 503 | 不同响应 body |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 8 | 0 | 0 | 1 |
| 16 | 16 | 0 | 0 | 1 |
| 48 | 48 | 0 | 0 | 1 |

主动停止 MySQL 后，对三个新的 requestId 连续发起请求：

| 次数 | HTTP | code | 服务端耗时 | requestId | details |
| ---: | ---: | --- | ---: | --- | ---: |
| 1 | 503 | `REVIEW_M0_STORAGE_UNAVAILABLE` | 5036 ms | 存在 | 0 |
| 2 | 503 | `REVIEW_M0_STORAGE_UNAVAILABLE` | 5020 ms | 存在 | 0 |
| 3 | 503 | `REVIEW_M0_STORAGE_UNAVAILABLE` | 5030 ms | 存在 | 0 |

停库延迟最小/中位/最大为 5020/5030/5036 ms，全部由服务端在 15 秒内返回；不依赖客户端先取消。三次 HTTP 500 为 0。

ai-service stdout/stderr 共 2 个文件、47 行。扫描结果：SQL 0、Java stack-frame 0、Throwable header 0、连接 URL 0、凭据 0、JWT 0、objectKey 0、hash/digest 0、body 0、generated security password 0；安全 diagnostic 5，其中精确 storage-unavailable diagnostic 3，reasonCode/requestId/category/retryable/count 均存在。

file-service 原生 fat Jar `/v3/api-docs` HTTP ready；stdout/stderr 共 2 个文件、35 行，generated security password 为 0。

在正式计数前，M0-01 首次执行发现已记录的 parser runtime classpath 前置产物不存在；按既有 handoff 的精确前置命令生成该产物后，原选择器不变并达到冻结计数。fat Jar 门正式执行前还有一次宿主 JVM native-memory 启动失败；没有进入业务门，错误转储已清理，调整本任务 JVM 资源界限后正式门一次完整通过。两项均未通过重跑掩盖业务断言失败。

## 6. Review M0 全量自动回归

| 门 | 结果 | 状态/说明 |
| --- | --- | --- |
| M0-01 | `100 discovered / 87 passed / 13 frozen expected skip` | 0 failure/error |
| 默认 acceptance | `26 / 26 / 0 / 0 / 0` | `ASSET_VERIFIED`；扫描 1215 个文件 |
| Integrated acceptance | `41 / 41 / 0 / 0 / 0` | `AUTO_VERIFIED / MANUAL_PENDING` |
| Integrated adapter | `15 / 15 / 0 / 0 / 0` | 15 个 target 全通过 |
| M0-02 | `77 / 77 / 0 / 0 / 0` | model/store/job/idempotency |
| M0-03 | `21 / 21 / 0 / 0 / 0` | checklist/privacy/extraction |
| M0-04 | `83 / 83 / 0 / 0 / 0` | archive/file/material |
| M0-05 | `50 / 50 / 0 / 0 / 0` | review/report/evidence |
| M0-06 | `38 / 38 / 0 / 0 / 0` | api/config；含新增 A-FIX4 合同 |
| Wave 2 | `6 / 6 / 0 / 0 / 0` | exit 0 |
| Wave 3 | `23 / 23 / 0 / 0 / 0` | exit 0 |
| Wave 3 recheck | `13 / 13 / 0 / 0 / 0` | exit 0 |
| FIX1 冻结 23-method selector | `23 / 23 / 0 / 0 / 0` | exit 0 |
| R1-C ai | `14 / 14 / 0 / 0 / 0` | exit 0 |
| file F3/F4 superset | `28 / 28 / 0 / 0 / 0` | owner/JWT/integrity/logging |

M0-01 的 13 项只保留冻结的预期边界；没有修改 skip。真实 MySQL 专项已由第 4、5 节独立运行。文件服务测试上下文会由 Spring 测试框架打印临时 generated password；这不属于原生 fat Jar 运行日志，原生 file/ai fat Jar 的该计数均为 0。

## 7. 旧能力回归与 LOCAL_ONLY

| 能力/门 | 实际结果 | 边界 |
| --- | --- | --- |
| Memory V1 | 资产 `16/16` + Integrated `171/171` = `187/187` | 0 failure/error/skip；异步 2/2；Web production build 成功 |
| MCP Search | `80 / 80 / 0 / 0 / 0` | 仅本地 mock；无外部 MCP 调用 |
| Qdrant QV04 | `112 / 112 / 0 / 0 / 0` | 确定性 SHA-256 vector；无真实 Qdrant/远程 Embedding |
| Document | `4 / 4 / 0 / 0 / 0` | mock 回归；真实 OCR/network 未运行 |
| AllModelsTest | `1 / 1 / 0 / 0 / 0` | 四模型 disabled；MCP 未创建远程客户端 |
| Agent/Conversation/JDBC | M0-01 精确组合已通过 | 公共 Hikari/配置无回归 |

`npm ci --no-audit --no-fund` 成功安装 284 个包；Memory 的 Vite production build 成功，只保留既有大 chunk 警告。MCP runner 的脚本终态文字仍为 `REVIEW_REQUIRED`，但其本地精确门和静态门实际为 80/80；QV04 runner 的脚本终态仍为 `PENDING_INTEGRATION`，因为 QV01-QV03 外部硬门不在本任务范围，QV04 本地资产实际为 112/112。没有把这两个脚本状态外推为真实外部能力通过。

公共 Hikari/配置变化由 Memory 187/187、M0-01 中的 Conversation/JDBC 精确测试、A-FIX4 Hikari/预算合同及 AllModels 应用上下文共同验证未见回归。远程模型全部 disabled；MCP 只运行本地测试；真实 Redis、Nacos、RabbitMQ、MinIO、OCR、远程模型、Tavily、Qdrant/Embedding、浏览器/UI 和人工验收均不由本任务宣称通过。

## 8. 清理、剩余风险与下一步

- 本任务 MySQL 容器、任务网络和卷在各 runner 的 `finally` 中精确清理；未触碰其他任务资源。
- 原生 Jar 进程和占用端口已停止；临时明文日志、JVM 错误转储和一次性 runner 在交付前清理，不进入 Git。
- 工作树交付时要求清洁；最终清洁状态、任务容器/进程/端口计数由交付回执给出。
- 剩余风险仅为本任务明确未运行的外部/人工边界，以及完整 A 尚未执行。本结果不能替代完整 A，也不能授权 B/C/D。

结论：`FIX_INTEGRATION_R2_READY`，授权下一任务“完整重跑 A”。
