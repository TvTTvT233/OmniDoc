# Review M0 A-FIX5 认证修复集成及全量自动回归 R1

## 1. 判定与边界

- 判定：`FIX5_INTEGRATION_READY`。
- 授权：`A_AUTHORIZED`，只授权下一任务基于本分支最终唯一 HEAD 执行“完整重跑 A”。
- 本任务只完成 A-FIX5 集成和同一最终集成代码 HEAD 的全量自动回归，不是完整 R4-A；未运行完整 A，未启动 B/C/D。
- 未移动 `milestone/review-m0`，未修改主 checkout、既有 worktree、原 integration 分支或 milestone；未开发 UI、P-08、P-09，未调用远程模型或远程 MCP。
- P-08 保持 `LOCAL_ONLY`；P-09 保持 `NOT_RUN / PENDING`。本判定不是 `HUMAN_ACCEPTED`。

已验证产品代码与测试的唯一 HEAD 为 `9c9731eac09eaf1564c1c5790c6ac609995c99e9`。本文件与 `docs/PROGRESS.md` 是其后的纯文档交付提交；Git 提交不能在自身内容中稳定自引用自身 SHA，最终结果 SHA 由交付回执和 `git rev-parse HEAD` 给出。

## 2. 固定输入、隔离与 ancestry

| 项目 | 实际值 |
| --- | --- |
| 保存项目根 | `C:\Users\lab\Desktop\docAI-main`（不是 Git 仓库） |
| Git 仓库 | `C:\Users\lab\Desktop\docAI-main\OmniDoc` |
| 固定集成基线 | `84228b09cae2d3c0bfd5ae65c85d8af32422b23f` |
| 分支 | `integration/review-m0-a-fix5-r1` |
| worktree | `C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-a-fix5-integration-r1` |
| A-FIX5 实现与测试 | `d2bee5a6c6e9f99e85e339bc19275bab2b06cf8b` |
| A-FIX5 handoff | `b81e44411e07d34fc2a49ae968902c878cb950fc` |
| A 失败证据 | 只从 Git object 读取 `291c0e1249b9a245e0c7063626ec4ffd701def40:docs/review-m0/handoffs/M0-R4-A-MYSQL8-R2.md`，未作为代码基线 |

已逐项确认：固定基线是实现提交的祖先，实现提交是 handoff 的祖先；两个输入提交和输入分支工作树均清洁。新 worktree 从固定基线创建，未覆盖或删除任何既有路径。

## 3. 集成顺序与产品差异

| 顺序 | 来源 | 本分支提交 | 结果 |
| ---: | --- | --- | --- |
| 1 | `d2bee5a6c6e9f99e85e339bc19275bab2b06cf8b` | `a36fe11` | cherry-pick 成功，无冲突 |
| 2 | `b81e44411e07d34fc2a49ae968902c878cb950fc` | `9c9731e` | cherry-pick 成功，无冲突 |

没有集成修正提交，也没有额外产品修改。`common` 相对固定基线无差异，公共 `JwtAuthenticationFilter` 未修改。生产 `permitAll` matcher 集合未扩大或缩小；Review 路径没有匿名开放。

## 4. 401 / 403 / 404 / 503 决策矩阵

| 场景 | 最终语义 | 验证 |
| --- | --- | --- |
| Review 缺失、空 Bearer、非 Bearer、错误签名、过期、畸形、解析异常 | `401 / AUTHENTICATION_REQUIRED`；`errorCode/message/details/requestId` 四字段，空 details，合法 requestId | 生产 fat Jar + 真实 SecurityFilterChain/JwtUtils，7/7 |
| 已认证但权限不足 | `403` | 既有 AccessDeniedHandler 与生产链定向测试保持；仓库当前没有可独立构造的真实 authority-limited Review URL，未伪造网络样本 |
| 合法 owner 创建、读取 | `201 / 200` | 原生网络通过 |
| 合法 owner B 跨 owner、合法 JWT 读取不存在资源 | `404 / 404`，不泄漏存在性 | 原生网络通过 |
| 非 Review 受保护端点匿名访问 | 既有 `403` | 原生 `/api/ai/conversations` 通过 |
| MySQL 不可用且 JWT 合法 | `503 / REVIEW_M0_STORAGE_UNAVAILABLE`，四字段且 requestId 合法 | 连续 3 次原生网络通过，均小于 15 秒；未被认证入口吞成 401/403 |

`ReviewM0RequestIdFilter` 的 Review-only 注册、相对 Security chain 的顺序、客户端 requestId 不可信、每请求只执行一次和非 Review 行为均由完整生产链测试锁定；没有发现重复执行、requestId 泄漏、其他 API 状态改写或公开面变化。

## 5. A-FIX1～A-FIX5 与 Review M0 自动回归

计数均为 `discovered / passed / failures / errors / skipped`。

| 门 | 结果 |
| --- | --- |
| A-FIX5 完整生产 SecurityFilterChain + 认证/API/日志基线组合 | `56 / 56 / 0 / 0 / 0`；其中 SecurityFilterChain `12 / 12` |
| A-FIX1～A-FIX4 核心 + storage envelope 组合 | `64 / 64 / 0 / 0 / 0` |
| M0-01 | `100 / 87 / 0 / 0 / 13`；13 项均为冻结 expected skip |
| 默认 acceptance | `26 / 26 / 0 / 0 / 0`，`ASSET_VERIFIED` |
| Integrated acceptance | `41 / 41 / 0 / 0 / 0`，`AUTO_VERIFIED` |
| adapter | `15 / 15 / 0 / 0 / 0` |
| M0-02 | `77 / 77 / 0 / 0 / 0` |
| M0-03 | `21 / 21 / 0 / 0 / 0` |
| M0-04 | `83 / 83 / 0 / 0 / 0` |
| M0-05 | `50 / 50 / 0 / 0 / 0` |
| M0-06 | `38 / 38 / 0 / 0 / 0` |
| Wave2 | `6 / 6 / 0 / 0 / 0` |
| Wave3 | `23 / 23 / 0 / 0 / 0` |
| Wave3 recheck | `13 / 13 / 0 / 0 / 0` |
| FIX1 冻结 selector | `23 / 23 / 0 / 0 / 0` |
| R1-C ai | `14 / 14 / 0 / 0 / 0` |
| file F3/F4 superset | `28 / 28 / 0 / 0 / 0` |

以上均按 A-FIX34 R2 handoff 的精确入口在 `9c9731e...` 上执行，未使用各来源分支历史结果替代。默认门扫描 1220 个 Git 可见文件；Integrated 为 26 个 asset 加 15 个 adapter。A-FIX1～4 的完整事务重试、幂等快照、连接预算、取消恢复、owner 隔离、安全诊断与日志语义均未退化。

## 6. 真实 MySQL 8、并发与原生 HTTP

- 旧真实 MySQL 8 runner 连续三个独立轮次，各为 `2 / 2 / 0 / 0 / 0`。
- 新 concurrent runner：seed `1 / 1 / 0 / 0 / 0`，restart verify `1 / 1 / 0 / 0 / 0`；双实例、事务回滚接管、409、owner 隔离和重启重放均通过。
- `file-service,ai-service -am -DskipTests package` 的 4 个 reactor 模块成功，使用生成的两个 Spring Boot fat Jar；没有 target 覆盖类。
- 原生 ai-service 使用真实 MySQL 8、生产 SecurityFilterChain/JwtUtils、合成任务专用 JWT、`LOCAL_ONLY`；Review schema 最大版本为 3。

| 同键并发 | HTTP 201 | HTTP 500 | HTTP 503 | 不同 body |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 8 | 0 | 0 | 1 |
| 16 | 16 | 0 | 0 | 1 |
| 48 | 48 | 0 | 0 | 1 |

合法 owner 的创建/读取为 201/200，跨 owner和不存在资源为 404/404，异载荷同键为 409。主动停 MySQL 后三次新合法 JWT 请求分别在 5022、5023、5037 ms 返回 `503 / REVIEW_M0_STORAGE_UNAVAILABLE`，HTTP 500 为 0；重启后 48 并发键重放保持 201 且 body 完全相同。

ai-service 与 file-service fat Jar 均 HTTP ready；OpenAPI 路径可达，schema v3，generated security password 为 0。精确验证 12 个既有 permitAll 路径全部没有变成 401/403，9 个相邻伪路径全部仍为 403；非 Review 保护端点仍为 403。

## 7. 日志扫描与 LOCAL_ONLY

最终有效原生运行扫描 4 个日志文件；scanner 统计 93 行，交付 runner 在终止进程后的直接行计数为 89（换行统计口径差异，不影响模式计数）。结果：

| 项 | 计数 |
| --- | ---: |
| SQL | 0 |
| Java stack-frame | 0 |
| Throwable header | 0 |
| connection URL | 0 |
| credential | 0 |
| JWT/token | 0 |
| objectKey | 0 |
| hash/digest | 0 |
| request/response body | 0 |
| generated security password | 0 |
| 安全诊断 `reasonCode/requestId/category/count` | 10 |

四个远程模型全部 `enabled=false`，`AllModelsTest` 实际远程成功为 0/4；MCP 明确输出 disabled 且没有创建远程客户端。所有测试与进程保持 `LOCAL_ONLY`，未调用远程模型或远程 MCP。

## 8. 旧能力与公共安全链回归

| 门 | 结果 |
| --- | --- |
| ConversationController / CurrentAuthenticationBaseline / Agent / SecurityConfig 相关精确组合 | 包含在 A-FIX5 `56 / 56`，全部通过 |
| Memory | `187 / 187 / 0 / 0 / 0`（asset 16 + integrated 171）；前端 42、async 2/2，Vite 1674 modules build 成功 |
| MCP | `80 / 80 / 0 / 0 / 0`；本地 mock，1220 文件静态扫描 |
| QV04 | `112 / 112 / 0 / 0 / 0`；本地确定性 SHA-256 vectors，1220 文件静态扫描 |
| Document | `4 / 4 / 0 / 0 / 0` |
| AllModels | `1 / 1 / 0 / 0 / 0`；四模型 disabled |

QV04 脚本因 QV01～QV03 外部硬门不在本任务范围而保留其整体 `PENDING_INTEGRATION` 文案；本任务只报告 QV04 本地确定性 112/112。没有把本地自动门写成远程、人工或外部环境验收。

## 9. 非正式前置噪声

以下均发生在正式产品断言前或来自无效 runner 配置，修正后从空任务资源重跑，未作为成功证据：Windows execution policy 阻止裸 `npm` 后改用 `npm.cmd`；PowerShell 5 未预载 `System.Net.Http`；MySQL CLI 密码 warning 被 strict mode 误判；readiness 路径/系统代理/GET 空 content 的 runner 问题；宿主低内存导致两次 JVM 启动前退出；首次 file-service runner 遗漏禁用 Rabbit listener，产生 Rabbit 连接 stack trace。最终有效运行显式禁用 Rabbit listener 并从零执行全部矩阵，日志扫描为 PASS。

## 10. 资源清理、剩余风险与授权

本任务独占的 `afix5int_*` MySQL 容器、匿名卷/网络、Java 进程、随机端口、合成 token/secret、临时 runner、runtime 日志、JVM crash/replay 文件、Maven/前端构建产物均在交付前清理；未触碰其他任务资源。工作树在交付提交后必须为 clean。

剩余风险：本任务没有运行完整 A，也没有运行 B/C/D；真实 MinIO、document/OCR 网络链、浏览器人工清单、远程模型/MCP、真实 Qdrant、P-08/P-09 均未验证。它们不能由本次自动门推导为通过。

在上述边界内，A-FIX5 集成和同一最终 HEAD 全量自动回归全部通过，结论为 `FIX5_INTEGRATION_READY`，只授权下一任务“完整重跑 A”：`A_AUTHORIZED`。
