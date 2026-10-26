# Review M0 F3：owner-aware 下载响应提交前完整性校验交接

## 1. 结论与 Git 隔离

- 状态：`HANDOFF_READY / PRECOMMIT_HTTP_VERIFIED / REAL_MINIO_PENDING`
- 固定起始基线：`fa7f1d8f8b3df984db84681bd15e55bd96c9a127`
- 起始分支核对：`milestone/review-m0` 精确指向上述 SHA
- 本任务分支：`fix/review-m0-f3-file-precommit-01a03966`
- 独立 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-f3-file-precommit-01a03966`
- 交接提交：本文与实现位于同一个可独立 cherry-pick 的提交；完整 SHA 由该分支最终 `HEAD` 和交付回执给出。Git 提交内容不能稳定自引用自身最终 SHA。
- 共享 checkout、R4 验收 worktree、其他修复 worktree和 `milestone/review-m0` 均未修改。
- 契约冲突：未发现。PDF/DOCX/XLSX/JPG/PNG/ZIP、ZIP 仅容器、`LOCAL_ONLY`、上传未确认不得 `MISSING`、`CANNOT_CONFIRM != FAILED`、报告不可变/`OUTDATED` 与 owner-null fail closed 均保持不变。

开始前已完整读取根 `AGENTS.md`、三份项目上下文、`docs/review-m0/README.md` 与 00..04 全部冻结文档、`M0-R3-FINAL-GATE.md`，并通过 `git show` 完整读取指定的 R4-A MySQL8 与 R4-B File/MinIO/JWT 交接。任务限制禁止修改 `docs/PROGRESS.md`，因此未按通用协作约定更新该文件。

## 2. 修复设计与不变量

1. `OwnerAwareFileService.open` 不再把只在 EOF 校验的对象流交给 Spring。它在返回 Controller 前，先把对象流以固定 64 KiB 缓冲写入任务私有临时文件，同时读到真实 EOF并计算大小与 SHA-256。
2. 只有真实 EOF、精确大小和数据库 SHA-256 三者全部一致，服务才返回可发送流。Controller 随后才构造 `200`、`Content-Length`、`ETag`、`Digest` 和 `X-Content-SHA256`，因此这些头只来自已完整预验证且与发送文件一致的字节。
3. 默认与不可突破的单文件 staging 硬上限均为 1 GiB；typed property `file.owned-download.maximum-bytes` 只能收紧到 `(0, 1 GiB]`。未修改任何公共 YAML，数值只属于运维安全包络，不是 P-02 产品容量承诺。
4. staging 根可通过 typed property `file.owned-download.staging-directory` 指定；默认位于 JVM 临时目录的专用子目录。根、实例目录、锁和 payload 均设置为当前 OS owner-only 权限；无法建立 owner-only 权限时服务启动 fail closed。
5. 每个进程使用唯一实例目录和独占锁。成功传输、客户端中止、摘要/大小失败、对象读取/打开异常都会删除 payload；Spring 上下文关闭会等待正在预验证的读取，关闭所有待发送流并删除实例目录；下次启动只清理精确命名且锁可获取的 stale 实例，不触碰活跃同机实例或其他路径。
6. 摘要不符、等长篡改、短读、长读、对象打开/读取失败和 staging 上限均同步抛出 `OwnedFileUnavailableException`，沿既有异常映射在响应未提交时返回冻结的 `503 OWNED_FILE_UNAVAILABLE`；失败响应没有完整文件字节、`ETag`、`Digest` 或 `X-Content-SHA256`。
7. owner 仍只来自已验证 JWT principal；不存在、跨 owner 和 owner-null 仍使用同一个 404 形状。MinIO 不可用、旧 owned/permitAll 隔离与 Review 的 503 映射未被改写。
8. `submissionFileName` 没有进入 file-service。本任务不修改 Review 材料、下载 Controller、原始文件名、对象、metadata、`objectKey`、hash 或字节；普通文件和 ZIP 单叶下载由 M0-04/M0-06 冻结回归证明未回归。
9. 新生产代码不记录临时路径、对象内部定位、摘要或正文，也没有新增日志。

## 3. 修改文件

### file-service 生产

- `file-service/src/main/java/com/javaee/fileservice/config/OwnedFileDownloadProperties.java`
- `file-service/src/main/java/com/javaee/fileservice/service/impl/OwnedFileDownloadStaging.java`
- `file-service/src/main/java/com/javaee/fileservice/service/impl/OwnerAwareFileServiceImpl.java`

### file-service 测试

- `file-service/src/test/java/com/javaee/fileservice/controller/OwnerAwareFilePrecommitIntegrityHttpTest.java`
- `file-service/src/test/java/com/javaee/fileservice/service/impl/OwnedFileDownloadStagingTest.java`
- `file-service/src/test/java/com/javaee/fileservice/service/OwnerAwareFileServiceTest.java`

### Review 兼容回归与交接

- `ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/CurrentFileServiceBaselineTest.java`
- `docs/review-m0/handoffs/M0-FIX-FILE-PRECOMMIT-INTEGRITY.md`

未修改 file-service/Review 生产 Controller、Review adapter、POM、公共配置、迁移、M0-07 selector/脚本、UI/路由、MySQL Review 索引、MCP、日志、document/OCR、远程模型或 `docs/PROGRESS.md`。

## 4. 实际验证命令与计数

计数顺序为 `discovered / passed / failures / errors / skipped`。

### 4.1 嵌入式真实 HTTP、JWT 与临时资源

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -am `
  '-Dtest=OwnerAwareFilePrecommitIntegrityHttpTest,OwnerAwareFileServiceTest,OwnerAwareFileSecurityTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

最终结果：`19 / 19 / 0 / 0 / 0`，`BUILD SUCCESS`。其中嵌入式真实 Tomcat + JDK `HttpClient` + 生产 JWT filter 为 `9 / 9 / 0 / 0 / 0`，覆盖：

- 正常下载及实际字节/Content-Length/ETag/Digest/X-Content-SHA256；
- 数据库摘要不符；
- MinIO 等价的对象等长篡改；
- 短读与长读；
- 对象打开异常和读取中异常；
- 16 MiB 测试配置硬上限；
- 客户端读取 1 KiB 后中止；
- 不存在、跨 owner、owner-null 相同 404；
- 服务关闭、重启、stale 启动清扫和最终 payload `0`。

失败场景均断言 `status != 200`（实际 503）、响应不是完整对象字节，并且三个完整性头均不存在。

权限与生命周期专项：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -am `
  '-Dtest=OwnedFileDownloadStagingTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`1 / 1 / 0 / 0 / 0`。在本机 Windows ACL 上核对 staging 根、实例目录和 payload 只有当前 owner 的 allow ACL；流关闭后 payload 不存在，服务关闭后实例目录不存在。

最终 file-service owner-aware 与旧冻结入口组合：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -am `
  '-Dtest=FileApplicationTests,OwnerAwareFilePrecommitIntegrityHttpTest,OwnerAwareFileSecurityTest,OwnerAwareFileServiceTest,OwnedFileDownloadStagingTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`21 / 21 / 0 / 0 / 0`，`BUILD SUCCESS`。

### 4.2 Review 文件兼容、M0-04 与 M0-06

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am `
  '-Dtest=CurrentFileServiceBaselineTest,HttpOwnedFileByteSourceTest,VerifiedReviewM0FileAccessTest,ReviewM0ConfigurationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' -DforkCount=1 `
  '-DargLine=-Xms96m -Xmx384m' test
```

结果：`19 / 19 / 0 / 0 / 0`，`BUILD SUCCESS`。

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am `
  '-Dtest=com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test,com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test' `
  '-Dsurefire.failIfNoSpecifiedTests=false' -DforkCount=1 `
  '-DargLine=-Xms96m -Xmx384m' test
```

结果：`104 / 104 / 0 / 0 / 0`，`BUILD SUCCESS`。普通文件、ZIP 单叶、逻辑下载名、Content-Disposition、owner 隔离和 M0-06 API/config 无回归。

### 4.3 默认自动门与编译

```powershell
.\scripts\review-m0\run-m0-acceptance.cmd `
  -ReportDirectory ai-service\target\review-m0-f3-default
```

结果：exit `0`；`26 / 26 / 0 / 0 / 0`；`ASSET_VERIFIED`。敏感数据检查扫描 1181 个 Git 可见文本文件，命中 0。默认资产门不代表生产外部链验收。

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests compile
```

结果：`BUILD SUCCESS`。

### 4.4 已修正的测试搭建错误

1. 嵌入式 HTTP 首次运行：`1 / 0 / 0 / 1 / 0`；`@TempDir` 在 per-class `BeforeAll` 前尚未注入，server 未启动。改为测试自行创建和精确清理唯一临时目录。
2. 第二次运行：`1 / 0 / 0 / 1 / 0`；最小测试应用仍装配 Nacos 自动注册且公共 YAML 的 8082 覆盖默认属性。测试启动参数显式禁用 Nacos/注册并使用命令行 `server.port=0`。
3. 修正后先为 `8 / 8 / 0 / 0 / 0`，补入对象打开异常和硬上限后最终 HTTP 套件为 `9 / 9 / 0 / 0 / 0`。两次错误均发生在测试基础设施阶段，没有产生被误计为通过的业务断言。

### 4.5 最终安全与差异检查

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command `
  "& 'scripts/review-m0/assert-no-sensitive-data.ps1' -RepositoryRoot '.' `
  -ReportPath 'ai-service/target/review-m0-f3-default/review-m0-acceptance-report.json' `
  -StaticScanRoots @('file-service/src/main/java/com/javaee/fileservice',`
  'ai-service/src/main/java/com/javaee/aiservice/review/m0')"
git diff --cached --check
```

最终敏感数据检查扫描 `1182` 个文本文件，命中 0；cached whitespace 检查退出 0。第一次用 `-File` 形式直接传逗号分隔的 PowerShell 数组时，两个根被解析成一个不存在路径，脚本在扫描前按设计非零退出；改用上面的 `-Command` 数组语法后通过，没有把未执行扫描计为成功。

## 5. 真实依赖与 NOT_RUN

| 依赖/边界 | 状态 | 说明 |
| --- | --- | --- |
| 嵌入式真实 HTTP server | `RUN / PASSED` | 真实 Tomcat socket、JDK HTTP client、生产 Controller/ExceptionHandler/Security/JWT、生产预验证实现 |
| 文件元数据/对象替身 | `RUN / DETERMINISTIC` | 合成 mapper 与可注入对象流；用于精确模拟摘要、短/长读、篡改与读取异常 |
| 真实 MinIO | `NOT_RUN` | R4-B 必须在修复集成后重跑；本任务不冒充 |
| 真实 MySQL 8 / file metadata DB | `NOT_RUN` | 不属于 F3；对象事实使用合成 metadata |
| 独立 file-service 生产 Jar | `NOT_RUN` | 嵌入式 Spring/Tomcat 不是独立部署进程 |
| ai-service -> file-service 双进程链 | `NOT_RUN` | Review 回归使用既有合成 HTTP/MockMvc 边界 |
| document-service / OCR | `NOT_RUN` | 明确不在范围 |
| 远程模型 / MCP / Qdrant / Memory | `NOT_RUN` | P-08 保持 `LOCAL_ONLY` |
| 浏览器 / UI | `NOT_RUN / P-09 PENDING` | 明确不在范围 |

## 6. 剩余风险

1. 真实 MinIO 对象 SDK、真实网络断流、MinIO 停机和真实数据库摘要注入必须由修复集成后的 R4-B 重跑；本提交只证明确定性对象流与真实 HTTP server 边界。
2. 预验证以磁盘换取响应提交前的确定性：下载开始前需要最多与文件大小相同的可用 staging 空间，延迟增加一次完整对象读取和一次本地文件读取。单文件硬上限为 1 GiB且可收紧；生产容量、并发和磁盘配额仍需运维测量，不是 P-02 产品承诺。
3. 优雅关闭和“上次进程遗留目录”的重启清扫已验证；没有在测试中强杀 JVM/OS。锁释放后的真实 crash recovery 仍应在 R4-B 服务重启场景复验。
4. Review 下载 Controller 的既有异步流式实现未在 F3 所有权范围内修改。真实 R4-B 应通过完整 ai-service -> file-service 链确认下游 503 在 Review 响应提交前传播；如果另发现 file-service 到 ai-service 之间的网络传输中断问题，应单独授权 Review adapter/下载边界修复，不能在 F3 越界修改 API Controller。
5. 本任务不处理 R4-B 已记录的日志脱敏、MCP 启动或 MySQL Review 索引缺陷；它们必须由各自修复提交闭合。

## 7. 集成顺序与复跑

1. 本提交从 `fa7f1d8f8b3df984db84681bd15e55bd96c9a127` 独立产生，文件范围与 MySQL/MCP/日志修复应无功能重叠；协调者按既定 Round5 修复顺序 cherry-pick，并在每步检查冲突和完整 SHA。
2. 在 R4-B 复验前必须同时集成：真实 MySQL 迁移修复、ai-service LOCAL_ONLY/MCP 启动修复、本 F3，以及日志脱敏修复；不得只集成 F3 后把历史 `NOT_RUN` 继承为通过。
3. 复跑本文件第 4 节全部命令，确认计数不降低；再使用真实 MySQL、真实 MinIO、独立 file-service/ai-service、双用户生产 JWT 重新执行 R4-B 全清单。
4. R4-B 必须重新验证数据库摘要不符、等长对象篡改、短/长/异常读取、客户端中止、MinIO/file-service 停机、双服务重启、普通文件与 ZIP 单叶、逻辑下载名不改原对象，以及临时 payload 残留为 0。
5. 本提交不修改 M0-07 selector/验收脚本，也不恢复或重写 R4-A/B/C/D；真实 R4-B 结果必须形成新的独立验收记录。
