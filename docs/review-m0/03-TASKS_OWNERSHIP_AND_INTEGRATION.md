# Review M0：任务、文件所有权与集成顺序

## 1. 状态与共同规则

- 本文只拆分 M0-01 至 M0-07；M0-00 是本目录的契约冻结任务。
- 所有实现任务必须从集成协调者指定的同一已提交基线创建独立分支和 worktree。
- 每个任务开始前完整读取仓库 `AGENTS.md`、三份项目上下文文档和本目录全部冻结文档。
- 子任务不得自行改变枚举、HTTP JSON、表字段、状态转换、错误码或证据要求。
- 需要改契约时先停止实现，回到独立契约变更任务；不能以“代码更方便”为由漂移。
- 状态枚举：`NOT_STARTED -> IN_PROGRESS -> HANDOFF_READY -> INTEGRATED -> AUTO_VERIFIED -> HUMAN_ACCEPTED`；`HANDOFF_READY` 只表示子任务可交接，不表示整体可用。
- 任何静态检查或合成测试不得被写成真实文件服务、MySQL、远程模型或浏览器验证。

## 2. 禁止并行修改的共享热点

M0-01 至 M0-07 子任务都不得修改：

- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/AgentExecutionService.java`
- `ai-service/src/main/java/com/javaee/aiservice/agent/execution/AgentToolRegistry.java`
- 仓库根 `pom.xml`
- 任一公共 `application.yml` / `application-local.yml`
- `web/src/router/index.js`
- `docs/PROGRESS.md`
- Memory、MCP、Qdrant 的迁移、门禁、报告或冻结文档
- 其他任务 worktree

如实现确实需要父 POM、公共配置、路由或进展记录，由集成协调者在所有子任务合入后单独核对并取得明确授权；不是任何子任务的隐含权限。当前依赖盘点显示 `ai-service` 已具备 JDBC、MySQL、OpenFeign、Jackson 和测试依赖，M0 不应为方便而升级公共依赖栈。

## 3. 包、资源与命名

生产包根冻结为：

```text
ai-service/src/main/java/com/javaee/aiservice/review/m0/
```

测试包根：

```text
ai-service/src/test/java/com/javaee/aiservice/review/m0/
ai-service/src/test/resources/review-m0/
```

迁移资源根：

```text
ai-service/src/main/resources/db/review-m0/
```

自动门禁根：

```text
scripts/review-m0/
```

前端 API/状态根在产品入口确认后使用：

```text
web/src/api/reviewM0.js
web/src/composables/useReviewM0.js
web/src/views/review-m0/
```

`P-09` 未确认前，不创建或修改路由，不把某个入口写成冻结产品事实。API 和后端人工验收不依赖路由。

## 4. M0-01：只读基线与契约验收资产

### 目标

验证既有文件/文档/认证/数据库能力的实际接口，建立只证明当前行为的基线与目标 fixture，不改生产实现。

### 独占文件

```text
docs/review-m0/handoffs/M0-01.md
ai-service/src/test/java/com/javaee/aiservice/review/m0/baseline/**
ai-service/src/test/resources/review-m0/baseline/**
ai-service/src/test/resources/review-m0/fixtures/**
ai-service/src/test/resources/review-m0/schema/**
```

### 必查项

- 文件服务所有者校验是否真实存在；
- `fileId` 元数据和下载是否能稳定得到原文件名、字节、大小和 SHA-256；
- ZIP 流式安全读取和单条目读取能力；
- 文档解析服务对 PDF/DOCX/XLSX/JPG/PNG 的实际能力与失败语义；
- `ai-service` 现有 JDBC 迁移启动方式；
- 认证上下文如何取得稳定 `userId`；
- 现有结构化 API 错误映射模式；
- 当前模块测试和应用上下文基线。

发现能力缺口只写交接与挂起目标，不在 M0-01 顺手修复。所有目标测试若尚未实现必须逐项 `Disabled/PENDING_IMPLEMENTATION`，不能以空断言通过。

### 出口

- 当前行为测试全通过；
- 每个缺口有所有者和后续任务；
- 不把本地 stub 结论写成真实服务已验证。

## 5. M0-02：领域、持久化与任务基础设施

### 目标

实现冻结枚举、聚合模型、全部 Review M0 表、CAS/幂等、租约/checkpoint、状态转换和过期基础设施。

### 独占文件

```text
ai-service/src/main/java/com/javaee/aiservice/review/m0/model/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/store/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/job/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/idempotency/**
ai-service/src/main/resources/db/review-m0/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/model/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/store/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/job/**
docs/review-m0/handoffs/M0-02.md
```

### 边界

- 不实现候选提取、材料 ZIP、审查算法或 Controller；
- 不修改 Memory 表/迁移；
- 迁移执行器必须复用已验证的项目启动机制或在本包内增加隔离实现，不能修改公共 YAML；
- 任务执行器提供注册式 `ReviewM0JobHandler` SPI，具体 Handler 由 M0-03/M0-05 提供，不依赖通用 Agent 注册表。

### 出口

- H2/MySQL mode 的 DDL、唯一约束、CAS 和事务测试通过；
- 状态机非法转换被拒绝；
- 租约失效可恢复、checkpoint 幂等、取消终态正确；
- 相同幂等键同载荷返回原资源，不同载荷冲突；
- 修订变化与当前报告过期同事务。

## 6. M0-03：官方依据、候选清单与远程授权

### 目标

实现官方依据手动分类、本地候选抽取适配、候选编辑/谱系、正式快照，以及可编辑脱敏预览和逐任务远程授权。

### 独占文件

```text
ai-service/src/main/java/com/javaee/aiservice/review/m0/checklist/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/privacy/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/extraction/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/checklist/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/privacy/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/extraction/**
docs/review-m0/handoffs/M0-03.md
```

### 边界

- 候选提取器输出只能写 `CANDIDATE_DRAFT`；
- 无可靠定位的模型文字不能伪造官方摘录；
- 用户改写保留 `origin` 和源引用并追加 `authorship=USER_REWRITTEN`；
- 正式快照不可变；依据变化使其 `STALE_BASIS`；
- 远程调用必须通过 `RemoteConsentVerifier` 验证 operation、revision、digest 和未消费状态；
- 本地模式测试不得初始化远程客户端；供应商和模型在 `P-08` 确认前只能使用测试适配口或显式外部注入，不能写默认产品配置。

### 出口

- 官方提取、用户新增、用户改写、停用谱系测试；
- 候选不能审查、未逐项确认不能发布、条件项未决不能发布；
- 预览修改使旧确认失效；摘要不匹配/授权复用被拒绝；
- `SEND_INTENT` 后崩溃且无可查询结果时以 `REMOTE_OUTCOME_UNKNOWN` 失败，不自动重发；
- 远程失败只使 Job `FAILED`，不发布快照或发现。

## 7. M0-04：材料、ZIP、逻辑命名与下载

### 目标

实现只读文件适配、六类顶层格式、ZIP 叶子材料、逻辑改名、分配、上传确认和单份副本下载。

### 独占文件

```text
ai-service/src/main/java/com/javaee/aiservice/review/m0/material/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/file/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/archive/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/material/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/file/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/archive/**
docs/review-m0/handoffs/M0-04.md
```

### 边界

- 文件适配口只能读取已认证用户拥有的文件；不得直连 MinIO 绕过文件服务；
- 普通文件和 ZIP 条目都计算内容摘要；ZIP 禁嵌套和路径穿越；
- 顶层非白名单返回 `415`；白名单输入的深度处理问题保留为 `CANNOT_CONFIRM`；
- `submissionFileName` 只写 Review 表；
- 下载以 `materialId` 定位，ZIP 只抽取单条目，响应头防注入；
- 具体安全硬上限用隔离的 typed properties 默认拒绝高风险输入，不修改公共 YAML；数值在 handoff 标为运维安全值而非产品事实。

### 出口

- 原件字节、原始文件名、文件表元数据和对象键前后不变的测试；
- 六类顶层、五类 ZIP 叶子、嵌套/加密/损坏/路径穿越/重复规范路径测试；
- 逻辑改名和 Unicode/RFC 5987 下载测试；
- 材料修订使上传确认失效，分配/名称修订只使报告过期；
- 跨用户读取与下载恒为不存在。

## 8. M0-05：确定性审查、报告与增量重审

### 目标

实现正式前置门、齐全性/命名确定性引擎、证据、总体状态、忽略事件、完整和单文件增量重审。

### 独占文件

```text
ai-service/src/main/java/com/javaee/aiservice/review/m0/review/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/report/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/evidence/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/review/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/report/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/evidence/**
docs/review-m0/handoffs/M0-05.md
```

### 边界

- 引擎只依赖 M0-02 的只读 Store SPI 和 M0-04 材料快照，不调用模型；
- 没有上传确认直接拒绝任务，不构造空集合缺失；
- 当前提交集合任一未决 `CANNOT_CONFIRM` 对整份报告抑制 `MISSING`，不得猜测材料用途；
- 确定性发现发布前验证两侧证据；
- 报告不可变，过期不可逆；
- 忽略通过事件折叠，不能删发现或提升 Ready；
- 增量报告必须与同输入的完整重审字节级规范结果等价（允许 report/job ID 和时间不同）。

### 出口

- `MISSING` 真值表、命名规则、证据、不确定性和系统失败测试通过；
- 全部过期触发器测试通过；
- 增量与完整属性测试通过；
- 取消/恢复/重试无重复报告或远程发送；
- 每个 `M0_READY` 都满足五项零阻塞条件。

## 9. M0-06：结构化 HTTP API 与组合

### 目标

按 `02-API_STORAGE_AND_ERRORS.md` 精确实现 Controller、DTO、认证所有者、错误映射、幂等 Header、下载响应和 Job 查询/取消，并完成 M0-02 至 M0-05 的生产装配。

### 独占文件

```text
ai-service/src/main/java/com/javaee/aiservice/review/m0/api/**
ai-service/src/main/java/com/javaee/aiservice/review/m0/config/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/api/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/config/**
web/src/api/reviewM0.js                 # 仅 P-09 确认后
web/src/composables/useReviewM0.js      # 仅 P-09 确认后
web/src/views/review-m0/**              # 仅 P-09 确认后
docs/review-m0/handoffs/M0-06.md
```

### 边界

- 不接收授权用 `userId`，不提供管理员绕过；
- 不修改旧 Controller 响应；
- 未知 JSON 字段拒绝；真实非 2xx 状态和 `{errorCode,message,requestId,details}`；
- API 路径/字段不得添加同义别名；
- 不修改 `web/src/router/index.js`。若 P-09 未确认，只交付后端 API；不能伪称页面已接入。

### 出口

- 每个冻结端点正/负契约测试；
- 跨用户 404、CAS 409、幂等冲突、上传未确认、远程授权、415 和 503 语义测试；
- 下载响应头、文件名注入和 ZIP 单条目测试；
- 旧 Agent/Conversation、文件和文档 API 直接回归无变化。

## 10. M0-07：独立自动验收与人工清单

### 目标

以黑盒/组合方式验证冻结契约；提供统一入口、机器报告、敏感信息扫描和人工验收清单，不修生产实现。

### 独占文件

```text
scripts/review-m0/**
ai-service/src/test/java/com/javaee/aiservice/review/m0/acceptance/**
ai-service/src/test/resources/review-m0/acceptance/**
docs/review-m0/handoffs/M0-07.md
```

### 边界

- 缺生产实现时门禁必须非零退出并报告 `PENDING_INTEGRATION`；
- 不为让门禁通过而修改生产代码；
- 报告不得含材料正文、预览正文、模型载荷/响应、对象键、预签名 URL 或密钥；
- 真实外部依赖未运行时明确 `MANUAL_PENDING`。

### 出口

满足 `04-ACCEPTANCE_CONTRACT.md` 的自动门禁，机器报告 Schema 通过，人工清单完整。

## 11. 分波与依赖

| Wave | 可并行任务 | 进入条件 | 退出条件 |
| --- | --- | --- | --- |
| 0 | M0-01；M0-07 仅验收资产骨架 | M0-00 已提交 | 基线缺口和 fixture 冻结；资产模式可运行但不能报通过生产 |
| 1 | M0-02 | Wave 0 交接 | 领域/DDL/Store/Job SPI 集成并通过阶段门 |
| 2 | M0-03 与 M0-04 | M0-02 已由协调者集成到共同新基线 | 两任务各自阶段门通过；无文件冲突 |
| 3 | M0-05 | M0-03、M0-04 已集成 | 确定性引擎与报告阶段门通过 |
| 4 | M0-06；M0-07 补全组合门禁 | M0-05 已集成 | API/装配通过；验收任务不改生产 |
| 5 | 协调集成与 M0-07 最终门 | 所有 handoff commit 可用 | 自动门禁通过，未验证项进入人工清单 |

M0-03 与 M0-04 只能从含 M0-02 的同一集成提交并行开始。M0-05 不能提前基于自己发明的临时 DTO 开发。M0-06 不能在上游未集成时复制领域类到 API 包。

## 12. 固定集成顺序

协调分支按以下顺序 cherry-pick/merge 并在每步复跑阶段门：

1. M0-01 测试资产；
2. M0-02 领域/存储/任务；
3. M0-03 依据/清单/隐私；
4. M0-04 材料/ZIP/下载；
5. M0-05 引擎/报告；
6. M0-06 API/装配；
7. M0-07 独立验收。

若 M0-03/M0-04 在并行期需要扩大 M0-02 SPI，只能各自在 handoff 中提出兼容性新增，由协调者形成单一适配提交；双方不得各自修改 M0-02 所有权文件。

## 13. 每个任务交接格式

每个 handoff 必须列出：

- 起始基线完整 SHA、分支、worktree；
- 交接 commit 完整 SHA；
- 修改文件；
- 冻结目标逐项状态；
- 实际命令、发现/通过/失败/跳过计数；
- 是否使用真实 MySQL、文件服务、文档服务、远程模型和浏览器；
- 未验证事项；
- 是否发现契约冲突；
- 集成前置和复跑命令。

不得仅写“测试通过”而没有命令、计数和外部依赖边界。
