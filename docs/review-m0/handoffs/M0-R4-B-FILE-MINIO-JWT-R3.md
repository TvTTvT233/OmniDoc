# Review M0 R4-B R3：File / MinIO / JWT 验收交接

## 1. 唯一结论

- Probe：`B_PROBE_FAILED`。
- Formal B1-B8：`NOT_RUN`。
- C：`C_NOT_AUTHORIZED`。
- 唯一有效 Probe 轮 `0903p4` 从 P1 开始完整执行到 P7。P1-P6 全部已执行断言通过；P7 对全部 Probe 日志递归扫描时，在 file-service stdout 中命中 `96` 个 64 位摘要值，首个失败断言为 `logs.hash`。只读补充分类确认，同一批 `48` 条文件元数据值输出还各包含内部 bucket 值与对象定位值；没有记录这些值。
- 有效轮在失败断言前累计 `125 passed / 0 failure / 0 error / 0 skip`；`logs.hash` 计为 `1 failure`。P7 中排在该断言之后的预签名 URL、SQL、stack、Throwable、generated password、body、运行时密钥逐值扫描、远程模型/MCP 成功计数均因 fail-fast 未继续，不能冒充通过。
- 这是明确、可复现的产品日志泄漏，不是 runner、环境或编排无结论。file-service 第一次进程命中 `72` 个摘要值，重启进程命中 `24` 个；ai-service 日志命中 `0`。当前产品配置 `file-service/src/main/resources/application.yml` 仍为 MyBatis 配置了 `org.apache.ibatis.logging.stdout.StdOutImpl`，与运行时元数据值输出相符。
- 按 Probe 闸门，Formal 没有启动，B8 自动回归也没有运行；未启动 C/D。

## 2. 输入、隔离与 Git

| 项目 | 实际值 |
|---|---|
| 产品候选 | `1da749fb22d8c61917376964088c10b23c834af2` |
| 候选分支 | `fix/review-m0-b-fix2-integrity-header-r1` |
| validation 分支 | `validation/review-m0-r4-b-r3-01a06793` |
| validation worktree | `C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-r4-b-r3-01a06793` |
| 冻结 runner 来源 | `10155ee489ec77c40e910eb8698a17425b06394f` |
| 本轮最终 runner 内容提交 | `7a50a3942b167db991806081e775ac506205f2e0` |
| 证据提交 | 本文件提交后的完整 SHA 由最终回执给出；Git 提交不能稳定自引用自身 SHA |
| milestone | `milestone/review-m0 = fa7f1d8f8b3df984db84681bd15e55bd96c9a127`，前后不变 |
| A 历史边界 | `edf10ac142fb09cb7c346a1bbc7820a553267b7c` 只对应旧候选 `a8e3440...`；没有在本候选上重跑或声称重跑完整 A |

相对产品候选只增加/修改验收资产和本 handoff：

- `scripts/review-m0/run-r4-b-file-minio-jwt.ps1`
- `file-service/src/test/java/com/javaee/fileservice/acceptance/R4BMinioSdkProbe.java`
- `docs/review-m0/handoffs/M0-R4-B-FILE-MINIO-JWT-R3.md`

没有修改产品源码、产品配置、迁移、产品测试、冻结 skip、旧 handoff/证据、冻结文档、`docs/PROGRESS.md`、UI、路由、C 或 D。

## 3. 冻结依据、历史映射与 runner 静态自检

开始前完整读取根 `AGENTS.md`、`docs/OMNIDOC_CONTEXT.md`、`docs/requirements/SCHOLARSHIP_REVIEW_V1.md`、`docs/PROGRESS.md`、Review `README.md`、`00` 至 `04`、`scripts/review-m0/MANUAL_ACCEPTANCE.md`、当前候选的 B-FIX1/B-FIX2、`M0-FIX-FILE-PRECOMMIT-INTEGRITY.md`、`M0-FIX-INTEGRATION-R1.md`，并通过 `git show` 完整读取 R4-B R2、R1 和原始 R4-B 三份指定 handoff。

从 `10155ee...` 恢复两份文件完整最终内容，而不是 cherry-pick delta。恢复后仅做明确 runner-only 强化：更新 R3 前缀/产品 SHA；对 `ETag`、`Digest`、`X-Content-SHA256` 重新计算实际响应体并核对三者一致；失败响应核对无成功头/无原字节；普通五格式 Review 下载全覆盖；日志和远程成功扫描递归；兼容 Windows PowerShell 5.1 的摘要转换和空日志读取。既有断言没有放宽。

启动真实资源前自检：

- PowerShell parser：`0 error`；`git diff --check`：exit `0`。
- GET/HEAD：GET 全部 `Body=$null`；没有 HEAD；multipart 只用于 POST。
- HTTP：JSON、multipart、客户端中止均使用 `ResponseHeadersRead`；status、headers、bytes、`readError` 结构化保留。
- MySQL：同时等待初始化完成标志和最终 `SELECT 1`；Review schema 必须精确为 `1,2,3`。
- SDK helper：使用 `file_metadata`，正常路径显式 `System.exit(0)`；对象数由本轮 SDK 枚举与本轮上传集合推导。
- 日志：递归枚举 `.log`；finally 只停止本轮 Java 进程、删除精确命名容器/网络/卷并复核残留。

历史映射没有只测最近失败：P4 重验原始 R4-B 的数据库摘要错配和 MinIO 等长篡改；P3 重验 R1 ZIP 单叶；P2/P3/P5/P6 重验 B-FIX1/B-FIX2 的字节、长度、摘要头、逻辑名和故障恢复。旧 Round5 的完整 Formal、重启矩阵、日志门与回归仍受 Probe 闸门约束，本轮按规则为 `NOT_RUN`，没有把 Probe 部分结果拼入 Formal。

## 4. 构建与唯一有效 Probe 环境

构建命令：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml clean
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am '-Dmaven.test.skip=true' package
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/r4b-runtime-classpath.txt' '-Dmdep.includeScope=test'
```

三项均 `BUILD SUCCESS`。Probe 使用这次从 `1da749f...` clean/package 得到的 file-service 与 ai-service fat Jar。

| 项目 | 唯一有效 Probe `0903p4` |
|---|---|
| 时间窗 | `2026-09-03 22:27:40` 至 `22:29:31`，Asia/Shanghai |
| 资源前缀 | `r4br3p0903p4` |
| MySQL | `mysql:8.0`；`8.0.46-1.el9`；digest `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b` |
| MinIO | `minio/minio:RELEASE.2025-09-07T16-13-09Z`；digest `sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` |
| Java / Maven / Docker | OpenJDK `17.0.19`；Maven `3.9.12`；Docker Client/Engine `29.6.2` |
| 容器 | `r4br3p0903p4_mysql`、`r4br3p0903p4_minio` |
| 网络/卷 | `r4br3p0903p4_net`；`r4br3p0903p4_mysql_data`、`r4br3p0903p4_minio_data` |
| Jar 回环端口 | file `45470`；ai `45471` |
| 其他端口 | MySQL、MinIO API/console 也为本轮独立随机回环端口；产品失败发生在 result JSON 写入前，数值未持久化，因此不猜测 |
| 身份 | 两个运行时合成用户；同源生产 HMAC JWT；密钥/token 未记录 |
| 外部边界 | 四个远程模型、远程 MCP、Rabbit listener、Nacos 禁用；`LOCAL_ONLY`；未启动 document/OCR、UI/浏览器、P09、C、D |

唯一有效 Probe 命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts\review-m0\run-r4-b-file-minio-jwt.ps1 `
  -Mode Probe `
  -EvidenceDirectory ai-service\target\r4b-r3-probe-0903p4 `
  -ResourceSuffix 0903p4
```

## 5. Probe P1-P7

| 阶段 | 实际结果 | 判定 |
|---|---|---|
| P1 环境/身份 | MySQL、MinIO、双 Jar ready；Review schema v3；缺失/无效 JWT 401；B 跨 owner 下载 404 且无成功完整性头；案例为 `LOCAL_ONLY` | PASS |
| P2 普通 PDF | 只经生产 HTTP 上传、创建 case、附加、下载；200、无 readError、长度/Content-Length/字节正确；三种摘要头存在、彼此同源且重新计算响应体后精确匹配 | PASS |
| P3 ZIP | ZIP 含两个可区分 PDF 叶及其他叶；按 materialId + stable locator 选中指定 PDF；200、无 readError、长度/字节正确；三种摘要头匹配所选叶且不匹配另一叶或整个 ZIP | PASS |
| P4 历史完整性 | 数据库摘要错配、MinIO 等长篡改各一次；均在提交前 503、无 readError、无完整原字节、无三种成功头；每次恢复后 200、字节和摘要头正确；payload 0 | PASS |
| P5 submissionFileName | 只经 Review HTTP 改为安全 Unicode 名；CRLF/路径名 400；Content-Disposition 无注入且解码后逻辑名精确；原名/大小/摘要/版本/bucket/objectKey 组合事实、对象数和字节不变；内容摘要头不变 | PASS |
| P6 故障恢复 | file-service 停机时 Review 503 且无成功头/原字节，恢复后 200；MinIO 停机时 file/Review 503 且无成功头/原字节，恢复后 200；字节和摘要头恢复；staging payload 0 | PASS |
| P7 日志/LOCAL_ONLY | 递归发现 6 份日志；正文、JWT、credential assignment、带标签 locator assignment 先各为 0；随后 64 位摘要值命中 96，明确失败。补充只读分类另确认内部 bucket 值 48、对象定位形状 48，均来自同 48 条 file metadata 值输出 | **FAIL** |

P7 在首个明确失败后停止，因此余下扫描项和远程成功计数保持 `NOT_RUN`；禁用启动参数不能替代扫描结论。

## 6. Formal B1-B8 与旧 R4-B/Round5 映射

Formal 的资源、进程、容器、数据、端口和构建均未创建。以下全部为同一个原因的 `NOT_RUN`：Probe 已明确产品失败，禁止进入 Formal。

| Formal 区域 | 状态 | 与历史/新场景关系 |
|---|---|---|
| B1 上传/认证/owner | NOT_RUN | Probe 只证明最小双用户/认证链；未用 Probe 代替正式六格式和完整 401 矩阵 |
| B2 owner-aware | NOT_RUN | 旧跨 owner、随机不存在、owner-null、重启、permitAll 风险均未作为 Formal 执行 |
| B3 MinIO/预提交完整性 | NOT_RUN | Probe 重验摘要错配/等长篡改；Formal 的六格式 SDK、短/长读、打开/读取异常、中止、完整重启矩阵均未执行 |
| B4 Review→file→MinIO | NOT_RUN | Probe 重验普通 PDF、ZIP 指定叶和 B-FIX2 头；Formal 六格式/全部叶矩阵未执行 |
| B5 submissionFileName | NOT_RUN | Probe 仅最小 Unicode/CRLF/路径；Formal 引号/特殊字符全矩阵与单副本全量未执行 |
| B6 故障/恢复/重启 | NOT_RUN | Probe 只做最小 file/MinIO 故障；Formal MySQL/MinIO/file/ai 全部适用重启未执行 |
| B7 日志/LOCAL_ONLY | NOT_RUN | Probe 已在日志门明确失败；Formal 日志集不存在 |
| B8 回归 | NOT_RUN | B-FIX1/B-FIX2、M0-04 88、M0-06 39、owner-aware 21、F3/F4 29、直接组合 152、Integrated 41、adapter 15 及旧精确 selector 均未运行 |

原始 R4-B 的摘要错配/等长篡改在 Probe 转为 PASS；R1 的 ZIP 单叶在 Probe 转为 PASS；R2 的三种完整性头在 Probe 转为 PASS。原始 R4-B 的敏感日志问题仍以新的真实命中复现，因此 B 仍失败。不能把这些最小 Probe 结果外推为 Formal 或 B8 通过。

## 7. runner-only 废弃轮

以下三轮全部发生在有效产品判定前，均在 finally 后核对本轮容器、网络、卷、Java/Maven 进程为 0，并删除各自 evidence/fixture/staging/log。它们不进入 P1-P7 计数，也不与 `0903p4` 拼接：

- `0903p1`：Windows PowerShell 不支持 `Convert.ToHexString`。
- `0903p2`：Windows PowerShell 不支持 `Convert.FromHexString`。
- `0903p3`：空 stderr 被 `Get-Content -Raw` 投影为 null，日志正则调用无结论。

对应 runner-only 修复提交依次为 `f2a953f2bab69d6cfbf40cc735b44a6ac4f3b768`、`a2ecb405d147a9259fb969d0dc06cb2d8cb06e3f`、`7a50a3942b167db991806081e775ac506205f2e0`；均未改变产品或放宽断言。

## 8. 清理与最终边界

有效轮失败后，runner finally 精确停止两个 Jar，并删除本轮两个容器、两个卷和一个网络。提交本 handoff 后执行全仓 Maven clean，并删除有效轮 evidence、fixture、staging、原始日志、classpath 和全部 target。

最终交付回执须再次确认：

- 所有 `r4br3p0903p1` 至 `r4br3p0903p4` 容器、卷、网络为 0；
- 本 worktree Java/Maven/runner 进程为 0；file/ai 端口 listener 为 0；
- target、fixture、staging、日志、evidence 为 0；
- validation worktree 干净，主 checkout 未修改；
- milestone 仍为 `fa7f1d8f8b3df984db84681bd15e55bd96c9a127`。

本任务没有合并、部署、移动 milestone、运行完整 A、启动 Formal/C/D 或修改产品。下一次只能由新的产品候选修复 file-service 生产日志中的摘要/bucket/object locator 值输出后，从新的独立 validation worktree、新资源前缀重新执行完整 Probe。
