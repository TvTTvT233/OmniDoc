# Review M0 F4-R1：敏感日志最小替代提交交接

## 结论

- 状态：`F4_REPLACEMENT_READY`
- 唯一起始基线：`fa7f1d8f8b3df984db84681bd15e55bd96c9a127`（`milestone/review-m0`）
- 分支：`fix/review-m0-f4-r1-sensitive-logging-01a04691`
- worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-f4-r1-sensitive-logging-01a04691`
- 替代提交：本文件与代码处于同一个单父提交，提交对象无法在自身内容中自引用其 SHA；完整 SHA 由最终交付回执给出。验收时应以 `git rev-parse HEAD` 为准，并核对 `git rev-parse HEAD^` 等于上述基线。
- 未 cherry-pick/revert 旧 F4，未合入 F1/F2/F3，未移动 milestone，未修改共享 checkout。

## 旧 F4 越界修改清单（绝不带入）

审计对象：旧 F4 `6ce350809da19aa71bdd5ba0072fdf1a893df71b`，并交叉读取 FI 失败证据 `e7fd6f16c4ebf01ccfd557c7bef404cfbd6eb8bc`。

- 旧 F4 共改 23 个文件（19 个生产文件），约 `+707/-280`，不是行级日志修复。
- 27 处既有 `throw new` 被替换，包含异常类型、消息和 cause 改写；特别包括 MinIO 异常从带 cause 的 `RuntimeException` 改成无 cause 的 `IllegalStateException`。本替代提交的生产 diff 中 `throw` 增删均为 0。
- 9 处可观察失败正文被改变，涉及 `SkillController` 的 `Result.fail`、`FileController` 的 `Result.fail`/错误 Map、`FileMetadataController` 的 500 body。本替代提交的 `Result.fail`、返回语句增删均为 0。
- 旧 F4 改写了 Skill 返回 Map、文件下载技能异常文本，以及 Agent/File controller/service 的 catch/return/throw 语义；本替代提交均未带入。
- 旧 F4 新增拒绝式 `UserDetailsService` 和全局 MyBatis `NoLogging` 配置；本替代提交均未带入。
- 旧 F4 对 `FileMetadataController` 的响应改写未带入；该文件在本替代提交中未修改。
- 旧 F4 对 `SecurityConfig` 的扩张未带入；两个现有 `SecurityFilterChain`、matcher 顺序和 JWT filter 均保持基线字节不变。

## 修改文件与生产差异理由

### 仅默认用户自动配置

- `ai-service/.../AiServiceApplication.java`：只导入并在 `@SpringBootApplication` 排除 `UserDetailsServiceAutoConfiguration`。
- `file-service/.../FileServiceApplication.java`：同上；原 `scanBasePackages` 保持不变。

这是两个无状态 JWT API 消除 Spring 默认用户/生成密码提示的最小显式调整；未硬编码用户/密码，未关闭 JWT，未修改任何授权 matcher。

### 仅日志调用/参数

- ai-service：`AgentExecutionService`、`SkillController`、`InternalService`、`SkillExecutor`、`FileDeleteService`、`FileDownloadService`、`FileUploadService`、`FileVersionService`、`RecycleBinService`、`MinIOServiceImpl`、`FileDownloadSkill`。
- file-service：`FileController`、`FileMessageListener`、`FileMetadataServiceImpl`、`FileServiceImpl`。

以上文件只删除或匿名化日志中的用户/文件/桶/对象定位符、原文件名、预签名访问地址、连接配置、确认 token、异常正文和堆栈；安全的大小、计数、耗时或静态阶段文本保留。所有 if/switch/循环、服务调用、数据库/对象存储调用、catch、throw、return、事务和工具路由保持原样。

### 新增测试

- `ai-service/.../security/SensitiveLoggingR1Test.java`：验证自动配置排除、MinIO 运行时标记不进入日志、目标输出调用的静态约束。
- `file-service/.../security/SensitiveLoggingR1Test.java`：验证自动配置排除、mapper 异常正文和消息 payload 不进入日志、目标输出调用的静态约束。
- `file-service/.../controller/OwnerAwareJwtCompatibilityR1Test.java`：明确锁定缺失、无效、过期 JWT 均为 401；测试切片自身也排除默认用户自动配置。

## 行为差异审计

对基线与当前 17 个修改生产文件做逐文件规范化比较：仅剥离日志/System 输出语句、注释以及两个 Application 的明确自动配置差异后，`normalized_business_mismatches=0`。

零上下文 diff 关键字审计：

| 项目 | 删除 | 新增 |
|---|---:|---:|
| `throw` | 0 | 0 |
| `return` | 0 | 0 |
| `if` | 0 | 0 |
| `switch` | 0 | 0 |
| `for` | 0 | 0 |
| `while` | 0 | 0 |
| `Result.fail` | 0 | 0 |

因此旧 F4 的 27 处 throw 替换、9 处失败正文变化、Skill Map/Result 变化均未出现。`git diff --check` 通过（仅工作区换行转换提示）。

## 实际验收与计数

### 编译、定向回归和冻结门

- `mvnw -pl ai-service,file-service -am -DskipTests compile`：4 个 reactor 模块全部成功。
- 日志测试初次迭代：file 4/4 通过，ai 2/3 通过；唯一失败是新测试错误假设 MinIO builder 会拒绝合成 endpoint。只修正测试断言后复跑：7/7，0 failure，0 error，0 skipped。
- M0-01 初跑在未生成 document runtime classpath 时发现 91 项、1 failure、11 skipped；随后按冻结文档先执行 `dependency:build-classpath`，再执行精确 selector：**100 discovered / 87 passed / 0 failure / 0 error / 13 expected skipped**。冻结测试未修改；`CurrentFileServiceBaselineTest` 5/5。
- ai-service 精确回归（AgentExecutionService、Agent Controller、Conversation、文件访问/删除、安全与日志）：72/72，0 failure/error/skipped。
- file-service 最终 owner/JWT/日志回归：17/17，0 failure/error/skipped；其中缺失、无效、过期 JWT 401 明确为 3/3，既有同 owner、跨 owner/不存在 404 与上传 owner 绑定测试 4/4，owner service 6/6。
- 最终默认 Review 门：`scripts\review-m0\run-m0-acceptance.cmd -ReportDirectory ai-service\target\review-m0-f4-r1-final`，**26/26，exit 0，ASSET_VERIFIED**；敏感静态扫描 1181 个文本文件，0 命中。
- `mvnw -pl file-service,ai-service -am -DskipTests package`：4 个 reactor 模块全部成功并生成两个 fat Jar。

### fat Jar 运行时日志

主机启动前可用内存约 0.52 GiB，因此严格错峰，未启动容器或真实依赖；JVM 使用低上限（file `-Xmx128m`，ai `-Xmx160m`，并限制 metaspace/code cache/线程栈）。

#### file-service

- 单独启动 fat Jar，HTTP 端口 ready；`/v3/api-docs` 返回 200。
- 运行时缺失 JWT 401、无效 JWT 401；过期 JWT 401 由独立 WebMvc 安全回归补足。
- 用 11 个独立运行时标记覆盖 access key、secret、JWT 签名值、数据库/Redis/Rabbit 凭据、object locator、原文件名和无效 bearer；完整 stdout/stderr 共 45 行。
- 精确值命中 0，敏感字段模式命中 0，Spring 生成密码提示命中 0，ready 命中 1。
- 一次 legacy multipart canary 请求在无真实依赖和低内存条件下未完成，已随服务停止；不据此声称 legacy 上传运行时成功。旧 File API 契约由冻结 M0-01 的 5/5 和 controller/service 回归证明。

#### ai-service

- 原生 `java -jar` 完整启动在安全自动配置和 MinIO 初始化之后，精确复现基线 MCP 构造阻断：`McpToolCatalogAdapter.<init>()` / `No default constructor found`。该原生启动未 HTTP ready；生成密码提示命中 0。未修改 MCP 文件。
- 按任务允许的基线阻断例外，在 `ai-service/target`（Git 忽略目录）临时编译只增加构造器注入标记的覆盖类，通过 Spring Boot `PropertiesLauncher` 启动同一 fat Jar。该运行不改变仓库源码或提交内容，完整 ApplicationContext 成功，HTTP 端口 ready，启动日志出现 `Started AiServiceApplication` 1 次。
- 用 15 个运行时标记覆盖 access key、secret、JWT 配置与真实 bearer、数据库/Redis/Rabbit 凭据、模型凭据、bucket、object locator、原文件名和无效 bearer；另请求文件访问地址路径以执行目标日志链。完整 stdout/stderr 共 299 行。
- 精确值命中 0，敏感字段模式命中 0，Spring 生成密码提示命中 0，ready 命中 1。
- 新 FI 必须在合入 F2 后重新执行不带临时覆盖类的原生 ai-service fat Jar HTTP-ready 验收；本提交不把 F2 作为依赖，也不声称原生基线构造缺陷已修复。

### 认证/授权兼容说明

- file-service owner-aware：缺失/无效/过期 JWT 401；同 owner 正常；跨 owner与不存在使用相同 404 shape；旧 `/api/files/**` permitAll 顺序未改。
- ai-service 的既有 SecurityConfig 未改。额外运行时探测显示基线 `/api/ai/models` 对缺失/无效 JWT 返回 403；这不是本任务引入的变化。为遵守“不得放宽 matcher、不得改变既有认证授权”的硬约束，本提交未将其顺手改成 401；若产品要求 ai-service 也统一为 401，应另立安全契约任务。

## NOT_RUN 与待决风险

- 真实 MySQL、MinIO、Redis、RabbitMQ、OCR、远程模型：`NOT_RUN`。
- R4-B：`NOT_RUN`，不声称通过。
- 未启动或停止/删除任何其他任务容器；未启动 A/B/C/D。
- 基线若把异常正文拼入 response/throw 文本，本提交按硬约束保持异常类型、消息、cause、`Result.fail`、Map、errorCode/details 原样。此类响应泄露可能性是独立待决风险。
- 远程模型、document/OCR/RAG 区域仍有基线通用异常正文日志；固定范围明确禁止修改这些区域，故未扩项，建议独立审计。
- 基线公共 YAML 的 MyBatis `StdOutImpl` 可能在真实数据库路径打印 SQL 参数；任务禁止修改公共 YAML/POM，且旧 F4 的全局 NoLogging 配置被认定越界，因此本替代提交未带入。真实数据库路径未运行，需独立处置/复验。

## 资源清理与新 FI 输入

- 两个本任务进程均已停止；端口 38892、38893 listener 均为 0，相关 PID 均不存在。
- 本任务临时运行时覆盖类、JWT 生成器、日志、临时存储与启动脚本在交付前清理；未触碰 worktree 外路径。
- 清理核对后主机可用内存约 1.34 GiB。
- 新 FI 的准确输入：基线 `fa7f1d8f8b3df984db84681bd15e55bd96c9a127` + F1 `a6e9a02c42219cb032c1940567e47d32cbc5557d` + F2 `57ef44ba49280be8cb38b9d6bc33cf0b25f651a1` + F3 `9b139f8dcb167ba1f6cad056f60d75ad27ca857c` + 本交付回执中的 F4-R1 完整 SHA；不得再使用旧 F4 `6ce350809da19aa71bdd5ba0072fdf1a893df71b` 或失败 FI `e7fd6f16c4ebf01ccfd557c7bef404cfbd6eb8bc` 作为集成输入。
