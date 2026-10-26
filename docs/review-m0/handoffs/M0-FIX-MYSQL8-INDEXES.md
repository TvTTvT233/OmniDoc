# Review M0 F1：真实 MySQL 8 长索引与迁移兼容修复交接

## 1. 结论与隔离

- 状态：`READY_FOR_CHERRY_PICK`。
- 固定基线：`fa7f1d8f8b3df984db84681bd15e55bd96c9a127`。
- 分支：`fix/review-m0-f1-mysql8-019ff65c`。
- worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-f1-mysql8-019ff65c`。
- 实际 Git 根：`C:\Users\lab\Desktop\docAI-main\OmniDoc`。
- 最终交付：固定基线之上的单一提交；提交完整 SHA 由最终交付消息和该分支 `HEAD` 给出。Git 提交不能在自身内容中稳定记录自身 SHA。
- 只修改数据库键设计、相关 Review M0 持久化适配、迁移恢复和独立真实 MySQL 8 门；没有修改共享 checkout、R4 worktree、其他修复 worktree、milestone 分支、公共 YAML、根 POM、现有 `scripts/review-m0/**`、M0-07 selector/验收脚本、UI/路由、文件下载、应用启动或日志。

## 2. 设计不变量

### 2.1 固定长度键

两个超长物理键均改为 64 个十六进制字符的 SHA-256 摘要键，完整业务字段继续原样保存：

| 业务键 | 原 utf8mb4 最坏键长 | 新物理键最坏长度 |
| --- | ---: | ---: |
| ZIP `(source_id, normalized_entry_path)` | `64*4 + 2048*4 = 8448` bytes | `CHAR(64) = 256` bytes |
| 幂等 `(owner_id, http_method, canonical_path, idempotency_key)` | `128*4 + 16*4 + 1024*4 + 255*4 = 5692` bytes | `CHAR(64) = 256` bytes |

摘要输入不是字符串拼接。规范编码依次写入：32-bit 编码版本、长度前缀 domain、32-bit 字段数，以及每个字段的 32-bit UTF-8 字节长度和原 UTF-8 字节。ZIP 与幂等使用不同 domain。边界歧义和跨 domain 等价均有测试。

所有读取、重复键和冲突路径都按摘要定位后复核完整原值：

- ZIP 仍按完整 `source_id + normalized_entry_path` 唯一；同一完整路径仍为版本冲突，不同完整路径若摘要相同则 `REVIEW_M0_STORAGE_UNAVAILABLE` fail closed。
- 幂等仍按完整 `owner + method + canonical path + key` 隔离；同载荷重放、异载荷冲突语义不变；摘要命中但任一完整字段不等时 fail closed。
- 持久化行的摘要与完整字段重新计算不一致时 fail closed。
- 完整字段长度、utf8mb4、冻结枚举、ZIP 容器语义、owner 隔离、报告与快照语义均未缩短或放宽；没有 latin1、前缀唯一索引或非默认 InnoDB 参数。

真实 MySQL 并发暴露了同一摘要首次 claim 时的 InnoDB 瞬态死锁。claim 只对 Spring `TransientDataAccessException` 做最多 8 次有界退避重试；非瞬态错误不重试，重试耗尽转为存储不可用。外层业务事务传播语义和同 key 唯一创建者语义保持不变。

### 2.2 全索引审计

- 迁移器验证 V001 全部 39 个命名唯一键/普通索引的存在；在 MySQL 上还验证每个索引的精确有序列形状。
- 验证 22 张 V001 表和 V003 快照表共 23 个主键的精确有序列形状。
- 真实 MySQL 门从 `information_schema.statistics` 动态枚举全部实际索引，包括主键、命名索引和外键隐式索引；拒绝任何 `SUB_PART` 前缀索引，要求所有被索引字符列仍为 utf8mb4，并逐索引断言 16 KiB 默认页下最坏长度不超过 3072 bytes。
- 修复后最大的保留复合索引是 `uk_review_m0_job_idempotency`，最坏 `1916` bytes；checkpoint 主键为 `1276` bytes；幂等 response 索引为 `1024` bytes。两个新摘要键均为 `256` bytes。

## 3. 自动提交部分库与版本恢复

`ReviewM0SchemaInitializer` 现在统一拥有 V001→V002→V003 顺序及版本记录：

1. 只接受 `{}`、`{1}`、`{1,2}`、`{1,2,3}` 四种连续版本历史。
2. V001 未记录时，先枚举现有 `review_m0_%` 表并逐表验证精确列集合；未知表或未知列形状立即失败，不删除、不覆盖、不猜测数据。
3. 执行幂等 V001 后，完整验证列、23 张表中属于 V001 的 22 个主键和全部 39 个命名索引；只有验证完成才记录 version 1。
4. V002 支持已添加部分循环外键但未记录版本的续跑；四个外键的表/列映射及 `RESTRICT/NO ACTION` 规则完整验证后才记录 version 2。
5. V003 快照表的精确列、主键和两个已启用 CHECK 完整验证后才记录 version 3。
6. 最终启动严格要求版本 `1,2,3`、23 张表、全部冻结列/键、四个 V002 外键和两个 V003 CHECK。

真实 MySQL 8 门覆盖冷库、重复初始化、原失败位置留下的精确 8/22 表、V001/V002 旧库升级 V003、升级前幂等数据保留、旧 `COMPLETED` 无快照 fail closed。版本永远在对应形状完整验证后写入。

## 4. 修改文件

生产与迁移：

- `ai-service/src/main/resources/db/review-m0/V001__review_m0_core.sql`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/model/ReviewM0KeyDigests.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/store/ReviewM0SchemaInitializer.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/IdempotencyResponseSnapshot.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/JdbcReviewM0IdempotencyStore.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/ReviewM0IdempotencySnapshotSchemaInitializer.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/material/JdbcReviewM0MaterialRepository.java`

测试与独立门：

- `ai-service/src/test/java/com/javaee/aiservice/review/m0/model/ReviewM0KeyDigestsTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/material/JdbcReviewM0MaterialDigestKeyTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/idempotency/JdbcReviewM0IdempotencyStoreTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/idempotency/ReviewM0IdempotencySnapshotSchemaInitializerTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/store/ReviewM0DdlConstraintTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/store/ReviewM0SchemaInitializerTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/store/ReviewM0RealMysql8AcceptanceIT.java`
- `ai-service/src/test/resources/review-m0/mysql8/run-real-mysql8.ps1`
- `docs/review-m0/handoffs/M0-FIX-MYSQL8-INDEXES.md`

## 5. 实际验证

计数顺序为 `discovered / passed / failures / errors / skipped`。所有下列成功命令退出码均为 0。

| 验证项 | 实际命令/selector 摘要 | 结果 |
| --- | --- | --- |
| 摘要、迁移、DDL、幂等、材料、API snapshot 定向集合 | `-Dtest=ReviewM0SchemaInitializerTest,ReviewM0DdlConstraintTest,ReviewM0IdempotencySnapshotSchemaInitializerTest,JdbcReviewM0IdempotencyStoreTest,ReviewM0KeyDigestsTest,JdbcReviewM0MaterialDigestKeyTest,ReviewM0ApiIdempotencySnapshotTest` | `34 / 34 / 0 / 0 / 0` |
| 最终全命名索引形状回归 | `-Dtest=ReviewM0SchemaInitializerTest,ReviewM0DdlConstraintTest` | `17 / 17 / 0 / 0 / 0` |
| 真实 MySQL 8 | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8.ps1` | `2 / 2 / 0 / 0 / 0`；没有条件跳过 |
| M0-02 | `model.*Test,store.*Test,job.*Test,idempotency.*Test` | `77 / 77 / 0 / 0 / 0` |
| M0-04 | `archive.*Test,file.*Test,material.*Test` | `83 / 83 / 0 / 0 / 0` |
| M0-06 | `api.*Test,config.*Test` | `23 / 23 / 0 / 0 / 0` |
| R1-A | `ReviewM0ApiIdempotencySnapshotTest,ReviewM0IdempotencySnapshotSchemaInitializerTest` | `8 / 8 / 0 / 0 / 0` |
| Wave 2 | `integration.wave2.*Test` | `6 / 6 / 0 / 0 / 0` |
| Wave 3 | `integration.wave3.*Test` | `23 / 23 / 0 / 0 / 0` |
| Wave 3 recheck | `integration.wave3recheck.*Test` | `13 / 13 / 0 / 0 / 0` |
| 默认自动门 | `.\scripts\review-m0\run-m0-acceptance.cmd -ReportDirectory ai-service\target\review-m0-f1-mysql8-default` | `26 / 26 / 0 / 0 / 0`；`ASSET_VERIFIED`；1182 个文本文件敏感数据扫描零命中 |
| ai-service compile | `.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am -DskipTests compile` | `BUILD SUCCESS` |
| 结构检查 | `git diff --check`；DDL/校验器索引名集合比较 | 通过；`39 / 39`，无缺失或额外项 |

真实门使用临时专用 `mysql:8.0` 容器、随机 root 密码、随机本机端口、独立数据库、utf8mb4/`utf8mb4_0900_ai_ci`、默认 16 KiB InnoDB 页；测试断言实际版本为 8.0.x、字符集为 utf8mb4、页大小为 16384。门结束后精确删除本任务容器，当前 `review_m0_f1_mysql8_*` 容器数为 0。

开发过程先复现了 R4-A 的真实长索引失败；后续真实并发又发现并关闭 InnoDB 瞬态死锁。最终通过结果均来自修复后的全新临时 MySQL 容器，不使用 H2/mock 冒充真实边界。为恢复当时停止的 Docker engine 曾重启 Docker Desktop；受影响的四个既有 `r4http01a02-*` 容器已按原名恢复并确认 `Up`，没有删除或重建它们。

## 6. 真实依赖、NOT_RUN 与剩余风险

实际依赖：Docker Desktop、`mysql:8.0` 镜像、MySQL Connector/J、Maven/JDK 17。没有新增生产依赖或修改 POM。

按任务边界明确 `NOT_RUN`：

- MinIO、file-service 下载/预提交、双用户 JWT HTTP；
- ai-service 应用启动上下文；
- document-service/OCR、真实文档解析；
- 远程模型、RabbitMQ、Redis、浏览器/UI、人工验收；
- Integrated 生产门（本任务只要求且实际运行默认自动门）。

剩余风险：

- SHA-256 理论碰撞不能数学消除；实现已在所有命中/冲突路径复核完整原值并 fail closed，模拟碰撞覆盖 H2 与真实 MySQL。
- 修复直接更正尚无法在默认 MySQL 8 完成的 V001。非默认参数下曾人工完成、但列/键形状与本修复不同的历史 schema 会被严格拒绝，不做自动改表或数据猜测；需要先离线审计再制定显式迁移。
- runner 固定验证 MySQL 8.0 系列；未外推到 MariaDB 或 MySQL 9。

## 7. 集成顺序与复跑

建议从 `milestone/review-m0` 的固定基线先 cherry-pick 本 F1 单提交，使 DDL/迁移与持久化键先一致；再按协调者确定顺序集成互不重叠的 F2/F3/F4，最后在新的独立验收 worktree 复跑 R4。不要恢复 R4-A/B/C/D 的提交。

```powershell
git cherry-pick <最终交付完整SHA>
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ai-service\src\test\resources\review-m0\mysql8\run-real-mysql8.ps1
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.model.*Test,com.javaee.aiservice.review.m0.store.*Test,com.javaee.aiservice.review.m0.job.*Test,com.javaee.aiservice.review.m0.idempotency.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test' test
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 '-Dtest=com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test' test
.\scripts\review-m0\run-m0-acceptance.cmd -ReportDirectory ai-service\target\review-m0-f1-mysql8-default
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am -DskipTests compile
```
