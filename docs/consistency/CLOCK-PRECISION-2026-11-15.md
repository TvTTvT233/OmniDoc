# 文档索引任务时间精度修复

## 问题与修复

`document_index_task.next_attempt_at` 等字段使用 `DATETIME(6)`，而原服务传入纳秒精度时间。在 H2 MySQL 模式下，`2026-11-12T00:00:00.123456789Z` 写入后成为 `.123457`，立即领取时仍与 `.123456789` 比较，新任务因此被误判为尚未到期。

服务时钟默认仍为 UTC；统一在生成时间时截断至微秒，写入、租约和领取比较使用相同精度。不改表、不加领取重试或等待、不降低旧断言。新增固定 Clock 回归通过真实 mapper、事务和数据库验证新任务立即可领取，测试结束恢复原时钟。

该缺陷与远端 DOC-004 的间歇性 ERROR 相符，但首轮远端报告没有原始堆栈，不能断言它是该次失败的唯一根因。仍需集成后远端检查。

## 验证记录

- 2026-11-15 在旧时间实现上独立重跑精确新用例：1 项、1 failure（领取为空），确认不是靠延时或偶然运行速度复现。
- 修复后组合回归 53 次执行全部通过，失败/错误/跳过均 0，退出码 0。分别为时间测试类 17（1 新增 + 16 继承）、原事务类 16、结构化证据类 20（4 新增 + 16 继承）；存在继承重复，独立场景为 21，不能表述为 53 个独立场景。
- `git diff --check` 通过，改动仅服务时间精度、新回归和记录文档。
- 中间一次编译因移除仍被租约响应使用的 `ZoneOffset` import 失败；恢复 import 后重新运行，原始失败日志保留。
- 本轮未连接外部模型、真实 MySQL、浏览器；生产环境调度精度边界和远端 CI 仍由集成验收负责。

命令（Java 17，工作树根目录）：

```powershell
./mvnw.cmd -s .mvn/settings-local.xml -pl document-service -am '-Dtest=DocumentClockPrecisionTest#newlyEnqueuedTaskIsImmediatelyClaimableAtSubMicrosecondClockBoundary' '-Dsurefire.failIfNoSpecifiedTests=false' test
./mvnw.cmd -s .mvn/settings-local.xml -pl document-service -am '-Dtest=DocumentClockPrecisionTest,DocumentConsistencyTransactionTest,StructuredEvidenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
git diff --check
```

私有日志保留于工作树 `.local/clock-reproduce.private.log`、`.local/clock-after.private.log`、`.local/clock-final.private.log`，不提交。
