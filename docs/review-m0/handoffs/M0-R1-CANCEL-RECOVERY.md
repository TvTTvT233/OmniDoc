# Review M0 R1-B：持久化取消恢复交接

## 1. 交付元数据与范围

- 任务：`Review M0 / R1-B / CANCEL_REQUESTED restart recovery`
- 固定起始基线：`eb400ee3c9c5ee06afc82e600a5e673a4c4d88a9`
- 分支：`fix/review-m0-r1-cancel-recovery-01a02df1`
- 独立 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-r1-cancel-recovery-01a02df1`
- 交接提交：本文件与实现位于同一个可独立 cherry-pick 的提交；Git 提交不能在自身内容中可靠自引用最终 SHA，完整 SHA 以交付消息及该 worktree 的 `git rev-parse HEAD` 为准。
- 实施范围：仅 M0-02 既有 `job/**` store/dispatcher/SPI 和对应 job 测试；没有修改 model 状态机，因为冻结的 `CANCEL_REQUESTED -> CANCELLED` 转换已经完整。
- 明确未修改：`idempotency/**`、`api/**`、`config/**`、file-service、Review file/archive/material、`acceptance/**`、`scripts/review-m0/**`、公共 YAML、根 POM、Web、Agent/MCP/Qdrant/Memory、`docs/PROGRESS.md`。

## 2. 实现结论

### 2.1 Store 原子恢复批次

`ReviewM0JobStore` 新增有界 `recoverCancellations(now, maximumJobs)` SPI；JDBC 实现只扫描：

- `status=CANCEL_REQUESTED`；
- 没有租约，或原租约已经到期；
- 每轮最多 32 个生产默认值，SPI 硬上限为 256；
- 按取消时间、创建时间和 `jobId` 稳定排序。

每个候选继续调用冻结的 `ReviewM0StateMachines.requireJobTransition(CANCEL_REQUESTED, CANCELLED)`，并以 `jobId + version + status + lease eligibility` CAS 原子更新为 `CANCELLED`。CAS 竞争失败只表示其他 dispatcher 已合法推进，不写第二次终态，也不产生 `FAILED`。事务在崩溃窗口整体提交或回滚；重复扫描对终态无写入。

恢复查询与既有 `acquireNext` 完全分离。`CANCEL_REQUESTED` 不会被改回 `RUNNING`，不会解析 `jobType` 查找 handler，也不会增加 attempt、checkpoint、远程调用、报告或结果引用。

### 2.2 Dispatcher 生命周期恢复循环

`ReviewM0JobDispatcher` 复用同一 store，作为 Spring `SmartLifecycle` 在 Review 持久化启用时自动启动单一 daemon 恢复线程：

- 启动后立即扫描，随后固定延迟 1 秒；单轮批次有界，不忙轮询；
- 多实例各自扫描，但由数据库版本 CAS 收敛；
- `start()` 重复调用幂等；
- `stop()/close()` 可重复调用，停止调度、发出中断并以 1 秒为关闭等待上限；
- Review 持久化默认关闭时不启动恢复循环；
- 存储异常不会终止后续扫描；日志只记录稳定事件和异常类型，不记录异常 message、SQL、请求、材料、对象键或凭据，恢复后记录一次 resumed。

既有 `runNext` 业务派发、handler SPI、重试、checkpoint、发布及 API 取消入口未复制、未改写。

## 3. 冻结目标逐项状态

| 目标 | 状态 | 确定性证据 |
| --- | --- | --- |
| 持久化 `CANCEL_REQUESTED` 在新 dispatcher 启动后有界收敛 | `PASSED` | H2/MySQL-mode 中写入 RUNNING 取消，丢弃旧 dispatcher/store 实例，新实例在租约到期后自动转 `CANCELLED` |
| 不重新进入业务 handler、不发布或重复副作用 | `PASSED` | handler 调用计数为 0；checkpoint、report、remote_call 均为 0；result/error 均为空 |
| 双 dispatcher/线程竞争、CAS、重复启动幂等 | `PASSED` | 两个真实 dispatcher 通过屏障并发扫描同一行，仅一个恢复结果，最终 version 只增加一次；重复启动/重启不再写终态 |
| 租约与崩溃窗口安全 | `PASSED` | 有效租约期间保持 `CANCEL_REQUESTED`；到期后新实例收敛；恢复批次在单事务内执行 |
| `SUCCEEDED/FAILED/CANCELLED` 不变；API 202/200 不回归 | `PASSED` | 三类终态逐字段不变；M0-06 API/config 17/17 通过 |
| 扫描有界、可停止、异常可诊断且脱敏 | `PASSED` | 批次 2+1 测试、停止后扫描/写入计数不再增加、首轮存储异常后下一轮成功，日志未输出合成异常正文 |
| 复用 M0-02 store/dispatcher/SPI，不复制第二状态机 | `PASSED` | 只扩展既有 job SPI/JDBC store/dispatcher；复用冻结 model 状态转换；model 无修改 |

## 4. 修改文件

生产：

- `ai-service/src/main/java/com/javaee/aiservice/review/m0/job/ReviewM0JobStore.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/job/JdbcReviewM0JobStore.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/job/ReviewM0JobDispatcher.java`

测试与交接：

- `ai-service/src/test/java/com/javaee/aiservice/review/m0/job/ReviewM0CancellationRecoveryTest.java`
- `docs/review-m0/handoffs/M0-R1-CANCEL-RECOVERY.md`

## 5. 实际命令与计数

所有 Maven 定向测试均使用 `-DforkCount=1` 和小内存 Surefire `argLine`；计数顺序为 discovered / passed / failures / errors / skipped。

| 验证项 | 实际 selector / 命令摘要 | 结果 |
| --- | --- | --- |
| 新增恢复 + 既有直接回归 | `ReviewM0CancellationRecoveryTest,ReviewM0JobDispatcherTest,JdbcReviewM0JobStoreTest` | `13 / 13 / 0 / 0 / 0` |
| M0-02 job/model/store/idempotency | `com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test` | `69 / 69 / 0 / 0 / 0` |
| M0-06 API/config | `com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test` | `17 / 17 / 0 / 0 / 0` |
| Wave 2 | `com.javaee.aiservice.review.m0.integration.wave2.*Test` | `6 / 6 / 0 / 0 / 0` |
| Wave 3 | `com.javaee.aiservice.review.m0.integration.wave3.*Test` | `23 / 23 / 0 / 0 / 0` |
| Wave 3 recheck | `com.javaee.aiservice.review.m0.integration.wave3recheck.*Test` | `13 / 13 / 0 / 0 / 0` |
| 默认 asset gate | `scripts\review-m0\run-m0-acceptance.cmd` | `26 / 26 / 0 / 0 / 0`；`ASSET_VERIFIED`；敏感检查 1149 个文本文件无命中；gate exit 0 |
| ai-service compile | `.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am -DskipTests compile` | `BUILD SUCCESS` |
| Git whitespace | `git diff --check` | `PASSED` |

Maven 测试命令统一前缀/参数：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m'
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-DargLine=-Xms32m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k -Xshare:off' '-Dtest=<selector>' test
```

## 6. 外部依赖运行边界

实际运行：

- Java 17、Spring JDBC/事务、Spring lifecycle；
- 进程内 H2 MySQL mode；真实 Review DDL、数据库事务、版本 CAS；
- 真实 Java 多线程和两个 dispatcher 实例；
- 全部为合成测试数据，不含真实用户材料。

明确 `NOT_RUN`：

- 真实 MySQL 8、多进程/多主机故障注入；
- 真实 file-service、MinIO、document-service、OCR；
- 真实远程模型、供应商、网络、RabbitMQ/外部队列；
- 浏览器、UI、真实 JWT 多用户会话；
- 完整操作系统进程启停。重启语义由同一持久库上的新 store/dispatcher/lifecycle 实例确定性模拟，不冒充真实部署重启演练。

## 7. 剩余风险与契约核对

- 真实 MySQL 8 的锁等待、默认隔离级别和多节点时钟偏差尚未运行；CAS SQL 同时复核 version/status/租约到期，设计上不依赖 H2 专有行为，但仍需部署环境复验。
- 恢复线程使用技术安全默认：1 秒固定延迟、每轮 32、最大 256；本任务没有修改公共配置或把数值升级为产品承诺。
- `stop()` 对调度线程发出中断并最多等待 1 秒；若底层 JDBC 驱动无视中断且调用长期阻塞，会记录稳定警告并依赖 DataSource/进程关闭。这不改变事务原子性，但真实驱动关闭行为未验证。
- 本任务只补取消终态恢复，不新增通用业务 Job scheduler；既有外部调用方仍负责调用 `runNext` 处理 `QUEUED/RUNNING` 业务任务。
- 未发现冻结契约冲突；没有自行决定 P-08/P-09。

## 8. 集成顺序与复跑

该提交可直接在固定基线 `eb400ee3c9c5ee06afc82e600a5e673a4c4d88a9` 之后 cherry-pick，不依赖 HTTP 幂等快照、file-service 或 M0-07 验收资产变更。若同批集成其他 Review M0 R1 修复，保持各提交独立，全部 cherry-pick 后再按以下顺序复跑：

1. M0-02 job/model/store/idempotency selector；
2. M0-06 API/config selector；
3. Wave2；
4. Wave3；
5. Wave3 recheck；
6. `scripts\review-m0\run-m0-acceptance.cmd`；
7. `.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am -DskipTests compile`；
8. `git diff --check`。

不要以本提交结果替代 Integrated 最终门、真实 MySQL 重启演练或用户人工验收。
