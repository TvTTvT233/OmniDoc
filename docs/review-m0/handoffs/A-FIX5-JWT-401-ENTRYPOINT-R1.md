# Review M0 A-FIX5：JWT 401 认证入口 R1

## 结论

- 结果：`PASS / HANDOFF_READY`
- 产品基线：`84228b09cae2d3c0bfd5ae65c85d8af32422b23f`（`integration/review-m0-a-fixes-r2`，`FIX_INTEGRATION_R2_READY`）
- 失败证据：`291c0e1249b9a245e0c7063626ec4ffd701def40:docs/review-m0/handoffs/M0-R4-A-MYSQL8-R2.md`，仅用于读取取证，未作为产品代码基线
- 分支：`fix/review-m0-a-fix5-jwt-entrypoint-r1`
- worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-a-fix5-jwt-entrypoint-r1`
- 产品实现 SHA：`d2bee5a6c6e9f99e85e339bc19275bab2b06cf8b`
- 交付 HEAD：以本 handoff 提交后的分支 HEAD 为准
- 范围：仅认证入口修复和定向验收；未做修复集成、完整 A，未启动 B/C/D
- 环境：`LOCAL_ONLY`；远程模型与 MCP disabled

## 失败与根因

固定失败证据显示，候选 ai-service fat Jar 使用真实 `SecurityFilterChain` 与 `JwtAuthenticationFilter` 时，Review 受保护端点对缺失和无效 JWT 返回 403，而冻结合同要求 401 + `AUTHENTICATION_REQUIRED`。

根因是生产 `SecurityConfig` 只有 `anyRequest().authenticated()` 与 JWT filter，没有显式 `AuthenticationEntryPoint`。common JWT filter 对缺失、非 Bearer、空 Bearer、签名/过期/格式/解析失败都不形成认证，只继续过滤链；匿名认证最后被默认 `Http403ForbiddenEntryPoint` 处理成 403。MockMvc 控制器合同的 401 没有覆盖这一生产安全链路径。

## 修复设计

`SecurityConfig` 增加 `DelegatingAuthenticationEntryPoint`：

- `/api/review-m0/**` 使用 `ReviewM0AuthenticationEntryPoint`，写出冻结 `ReviewM0ApiDtos.ErrorResponse`：HTTP 401、JSON/UTF-8、`AUTHENTICATION_REQUIRED`、固定安全 message、有效 requestId、空 details。
- 其他受保护路径仍使用原 `Http403ForbiddenEntryPoint`，避免改变 Conversation 等既有 API 行为。
- `AccessDeniedHandler` 未改，未把 `AccessDeniedException` 全部改成 401；已认证但权限不足仍由 Spring Security 保持 403。
- common `JwtAuthenticationFilter` 未改，因此没有跨服务认证行为变化，也不需要跨消费者修改。
- 既有 permitAll matcher 原样保留，没有扩大或缩小公开面。

entry point 不使用或记录 `AuthenticationException` 的 message/cause，也不回显 token、解析原因、stack 或敏感 details。它复用冻结 DTO、错误码和 `ReviewM0RequestIdFilter.getOrCreateRequestId`。

## 过滤链顺序与 requestId

实际顺序为：

1. servlet 容器注册的 `ReviewM0RequestIdFilter`，order=`Ordered.HIGHEST_PRECEDENCE + 20`，URL=`/api/review-m0/*`；
2. `springSecurityFilterChain`，Boot 默认 order=`SecurityProperties.DEFAULT_FILTER_ORDER`；
3. 链内 `JwtAuthenticationFilter` 位于 `UsernamePasswordAuthenticationFilter` 前，并早于 `AnonymousAuthenticationFilter`；
4. `ExceptionTranslationFilter` 位于 `AuthorizationFilter` 前；
5. Review 请求没有有效 principal 时，由 Review entry point 写 401；进入控制器后的 Review 异常仍由冻结 controller advice 处理。

requestId filter 因此先于安全链创建 requestId 和 MDC correlation；entry point 与 controller advice 均复用同一个 request attribute。即使入口独立调用，也只生成符合 `req_[0-9a-f]{32}` 的 requestId，不读取客户端伪造值。

## 401 / 403 / 404 决策表

| 情形 | 结果 | 证据/边界 |
| --- | --- | --- |
| Review 缺失 Authorization | 401 + `AUTHENTICATION_REQUIRED` | 自动生产链与原生 HTTP 均通过 |
| Review 空 Bearer | 401 + `AUTHENTICATION_REQUIRED` | 自动生产链与原生 HTTP 均通过 |
| Review 非 Bearer | 401 + `AUTHENTICATION_REQUIRED` | 自动生产链与原生 HTTP 均通过 |
| Review 错误签名 | 401 + `AUTHENTICATION_REQUIRED` | 自动生产链与原生 HTTP 均通过 |
| Review 过期 JWT | 401 + `AUTHENTICATION_REQUIRED` | 自动生产链与原生 HTTP 均通过 |
| Review 畸形 JWT | 401 + `AUTHENTICATION_REQUIRED` | 自动生产链与原生 HTTP 均通过 |
| Review JWT 解析异常 | 401 + `AUTHENTICATION_REQUIRED` | 自动生产链与原生 HTTP 均通过 |
| 合法 owner JWT | 进入业务链；创建 201、读取 200 | 原生 fat Jar + MySQL 8 |
| 合法 JWT 跨 owner | 404 + `REVIEW_M0_CASE_NOT_FOUND` | 自动生产链 probe 与原生 HTTP |
| 合法 JWT 读取不存在资源 | 404 + `REVIEW_M0_CASE_NOT_FOUND` | 原生 HTTP |
| 已认证但权限不足 | 403 | 默认 AccessDeniedHandler 未改；当前产品没有 authority/role 限制端点，故没有可执行的真实规则样本 |
| 合法 JWT、存储不可用 | 503 + `REVIEW_M0_STORAGE_UNAVAILABLE` | 原生停库 HTTP，约 5.052 秒 |
| 非 Review 受保护端点匿名访问 | 保持既有 403 | 自动生产链及原生 `/api/ai/conversations` |

## permitAll 与公开面

生产白名单 matcher 未做任何文本或语义修改：actuator、Swagger/API docs、`/error`、根/首页/Agent workbench、静态资源、Agent、WebSocket、skills description 均维持基线。完整生产链测试逐项验证每个现有 matcher，并验证相邻伪路径仍受保护；原生门验证静态页与 API docs 为 200。没有通过 permitAll 放开 Review 路由。

## 修改文件

- `ai-service/src/main/java/com/javaee/aiservice/config/SecurityConfig.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/api/ReviewM0AuthenticationEntryPoint.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/api/ReviewM0RequestIdFilter.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/api/ReviewM0ApiExceptionHandler.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/config/ReviewM0Configuration.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/config/ReviewM0RequestIdConfiguration.java`
- `ai-service/src/test/java/com/javaee/aiservice/config/ReviewM0SecurityFilterChainTest.java`
- `docs/PROGRESS.md`
- `docs/review-m0/handoffs/A-FIX5-JWT-401-ENTRYPOINT-R1.md`

## 自动合同与定向回归

| 门 | 结果 |
| --- | --- |
| 新增完整生产 `SecurityFilterChain` 测试 | 12/12，0 failures/errors/skips |
| 新测试 + A-FIX2 安全日志/API 契约 + Current auth/structured baseline + Conversation + 503 envelope | 56/56，0 failures/errors/skips |
| 默认 Review acceptance | 26/26，`ASSET_VERIFIED`；敏感扫描 1219 个文本文件无命中 |
| Integrated Review acceptance | 41/41（asset 26/26 + adapter 15/15），`AUTO_VERIFIED / MANUAL_PENDING`；敏感扫描无命中 |
| M0-01 冻结精确选择器 | 100 discovered / 87 passed / 13 frozen expected skipped / 0 failures/errors |
| A-FIX1～4 核心选择器 | 64/64，0 failures/errors/skips；包含 bounded storage、failure classifier、503 envelope、配置、幂等/事务/迁移/job/report |

新增测试加载实际生产 `SecurityConfig`、common `JwtAuthenticationFilter`、Review entry point、requestId servlet registration 与 `RequestUserContext`，不是 entry point 单测。它覆盖七类无效认证、合法 owner、跨 owner、过滤器顺序、requestId 顺序/复用、全部现有 permitAll matcher 及相邻保护面，并确认非 Review 受保护 API 仍为 403。

## 原生 fat Jar HTTP 门

从结果源码执行 Maven reactor package，common 与 ai-service 构建成功并生成 ai-service fat Jar。使用隔离 MySQL 8、随机宿主端口、随机测试 DB/JWT secret、local profile、真实 `JwtUtils`、真实 `JwtAuthenticationFilter` 和真实生产 `SecurityFilterChain` 启动；Review persistence/auto-migration 开启，四路模型、MCP、memory 关闭。

| 请求 | HTTP | errorCode/结果 | envelope/requestId |
| --- | ---: | --- | --- |
| missing | 401 | `AUTHENTICATION_REQUIRED` | 4/4 字段，VALID |
| empty-bearer | 401 | `AUTHENTICATION_REQUIRED` | 4/4 字段，VALID |
| non-bearer | 401 | `AUTHENTICATION_REQUIRED` | 4/4 字段，VALID |
| wrong-signature | 401 | `AUTHENTICATION_REQUIRED` | 4/4 字段，VALID |
| expired | 401 | `AUTHENTICATION_REQUIRED` | 4/4 字段，VALID |
| malformed | 401 | `AUTHENTICATION_REQUIRED` | 4/4 字段，VALID |
| parse-error | 401 | `AUTHENTICATION_REQUIRED` | 4/4 字段，VALID |
| owner-a-create | 201 | SUCCESS | 进入真实业务/DB 链 |
| owner-a-read | 200 | SUCCESS | owner 可读 |
| owner-b-cross-read | 404 | `REVIEW_M0_CASE_NOT_FOUND` | 4/4 字段，VALID |
| owner-a-missing-read | 404 | `REVIEW_M0_CASE_NOT_FOUND` | 4/4 字段，VALID |
| storage-unavailable | 503 | `REVIEW_M0_STORAGE_UNAVAILABLE` | elapsed 5052 ms |
| static / API docs | 200 / 200 | permitAll 保持 | 冻结公开状态 |
| non-Review protected | 403 | 基线保持 | 未受 Review entry point 影响 |

七类 401 的 errorCode、message、四字段 envelope 和 requestId 形态完全一致，没有按 JWT 原因形成可枚举响应。

## 正式日志扫描

只计算最终闭环 fat Jar 运行日志：2 个文件、46 行。正式扫描器启用 `RequireDiagnostic`，结果：

| 检查项 | 命中 |
| --- | ---: |
| JWT/token | 0 |
| SQL | 0 |
| Java stack-frame | 0 |
| Throwable header | 0 |
| credential | 0 |
| body | 0 |
| connection URL | 0 |
| generated password | 0 |
| objectKey | 0 |
| hash/digest | 0 |
| 白名单 diagnostic | 2 |

安全诊断仅保留既有白名单结构字段。准备原生门时有若干 PowerShell/.NET 编排前置或传输脚本失败，均未形成完整产品断言并在 finally 中清理；本报告只采信最后一次完整闭环的 `NATIVE_GATE_PASS`。

## 公共影响与剩余风险

公共行为变化仅限 `/api/review-m0/**` 在未形成有效认证时由 403 修正为冻结 401。Conversation/Agent/Memory 的成功与错误路径、A-FIX1～4 的 MySQL/事务/幂等/连接预算/Hikari/安全日志策略均未修改。503 不会被认证入口吞并。

剩余风险：本任务按边界没有集成，也没有运行完整 A 或 B/C/D；集成到新候选后仍须完整重跑 A。当前产品不存在 authenticated-but-insufficient-authority 的实际 endpoint/rule，所以 403 由未修改的 Spring AccessDeniedHandler/配置边界保证，而不是用虚构测试 profile 制造产品规则。原生网络门是定向 MySQL 8 闭环，不替代完整环境与人工验收。

## 清理与交付状态

本任务使用的容器、fat Jar 进程、随机端口、临时 token/secret、编排脚本、运行日志和 Maven/acceptance 生成物在交付前清理；不触碰其他任务资源。未生成 diff/patch 文件，工作树要求保持干净。

`HANDOFF_READY = YES`
