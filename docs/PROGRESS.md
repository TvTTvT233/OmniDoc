# OmniDoc 项目进展

## 2026-10-16～17 分段403修复与验收

- 用户授权先修复分段接口把未索引误判为无权的问题；继续由本对话验收，三个子Agent分别实施后端、前端和运行验证。起点为 `integration/core-baseline` / `05c2d63`，未更新main、未推送、未合入Review M0。
- 真实复现：同一合成文档的文档API为200且归属匹配，Redis `doc` 与 `doc_segments` 都不存在，旧分段API仍为HTTP200/business403。证据为本轮 `before.json`。
- 新分段读取先通过JWT-only文档读权限窄接口核验active实体与ACL，再严格读取Redis。权限请求不信任 `X-User-Id`，不返回正文；原文档旧header回退接口没有在本轮统一重构。保留索引归属限制，缺元数据不自动放行。
- 显式区分NOT_INDEXED与SEGMENTS_AVAILABLE；无登录401、文档无权/不存在404、索引归属冲突403、分段残缺/不可验证409、依赖故障503。前端显示对应状态且保留重试，不将故障当成空结果；分段可读不声称向量化完成。
- 后端新增29项通过，包含在AI本地回归313项（310通过、真实Qdrant3项跳过）及document11/11中；两服务打包通过。前端专项33/33、独立startup1/1、既有Memory UI11/11通过；最终409文案修改后专项再次33/33，生产构建通过。已有大chunk警告保留。本轮未重复M07完整门禁。
- 真实HTTP权限与分段矩阵14/14、文档服务停机与Redis停机2/2通过。Redis503实测约21秒，不宣称故障响应性能达标。故障恢复后原未索引和合成分段状态恢复，四个模型仍禁用；10/17复核五服务、五隔离容器正常。
- 10/17真实Edge headless浏览器通过未索引、合成两段可读、他人拒绝3条，使用Vite dev与真实HTTP、无mock。主协调复核原始结果和三张截图。合成分段是直接准备的Redis读取夹具，未调用索引API、Embedding或写向量，不算真实索引构建/问答通过。
- 收尾仅删除本轮4个合成Redis键，清单目标剩余0；合成文档/文件/账号保留，用户原始材料未改。临时浏览器与Vite已停止，5后台服务/5隔离容器继续运行。清理及最终状态见 `fixture-cleanup.json`、`environment-final-2026-10-17.json`。
- 首次后端/前端测试布置问题及运行JAR被Windows锁定的首次打包失败记录保留；修正测试布置和停服解除锁后，以最终日志计通过。没有弱化权限或错误状态断言。
- 主协调结论：本轮分段读取403问题修复通过，汇总 `docs/baseline/RESULT-2026-10-17.md`；分项报告为 `segment-access-{backend,frontend,runtime}-2026-10-16.md`。证据位于Git忽略的 `.local/baseline-evidence/segment-access-2026-10-16/`，凭据不提交。
- 兼容边界：无真实文档实体的独立RAG旧记录现在404；文档协作者不能自动读取其他用户私有索引，管理员无该端点索引归属豁免；整文向量无分段列表可返回409，不等同向量损坏。其他RAG端点/旧文档身份兼容逻辑不在本轮修复范围。
- 后续待讨论：旧“AI已就绪”标记、文档/索引版本一致性、自动重索引和真实模型链路。原始材料不变，未自动进入下一开发阶段。

## 2026-10-15 分段业务403说明（只读定位）

- 用户询问上一轮报告中的分段403含义。本次读取 `RagController.getDocumentSegments/assertDocumentAccess`、`KnowledgeBase.getDocumentMetadata/addDocumentWithSegment` 和 `AiSecurityExceptionHandler`，未修改业务代码、未重跑服务。
- 确认接口先读取Redis知识库 `doc:{documentId}` 元数据的userId进行归属比较；没有记录时owner为空，普通登录用户同样被拒绝。该逻辑未向文档服务核对访问权限，也未区分“尚未索引”和“真实越权”。元数据读取异常还会返回空Map，因此单看403无法区分这些原因。
- 上轮浏览器样例是未索引文档，该状态与上述缺少知识库归属记录的拒绝路径一致；本次未重新读取该样例Redis数据，不扩大为所有403的已证实根因。
- 异常处理返回业务code403而未设置HTTP状态，因此当时观察到HTTP200/业务403。影响的是读取分段明细，不说明文件丢失、登录整体失败或模型额度不足；此前文档读写通过结论不受此错误展示影响。
- 建议修复方向：以可信文档权限核验为前提，明确区分未索引、真实越权和存储读取失败，分别返回对应状态；不可通过移除权限检查或缺少归属记录就放行解决。本次仅记录定位，尚未修复。

## 2026-10-14～15 核心运行基线验证（无需外部模型范围通过）

- 用户指定本对话负责主要验收和决策，执行由三个子 Agent 分担：运行环境/启动、后端回归/构建、前端真实状态修复。验收口径见 `docs/baseline/ACCEPTANCE.md`。
- 用户明确本轮先验证无需外部模型的部分；真实 Embedding、模型生成/Agent 规划和 Tavily 联调本轮未验证，不计作通过。
- 起点仍为 `integration/core-baseline` 的 `ca3423a`；保留上一轮文档变更，未更新 main 或推送。Review M0 不纳入本轮。
- 已在忽略目录准备便携 JDK 17，未修改系统默认 Java；首次全模块 package 实际 `BUILD SUCCESS`，生成 5 个服务 jar，但该构建跳过测试，不能作为测试通过证据。原始日志：`.local/baseline-evidence/backend-package.log`。
- 执行曾因子 Agent 额度限制中断，用户要求继续后已恢复。Docker Desktop engine 中途停止，正常重启后五个隔离依赖恢复；未确认根因，未修改其他项目容器/卷。
- 后端首次 MCP 80/80、AI 回归发现291/通过288/真实Qdrant跳过3、文档单测4/4。真实启动另暴露 MCP 目录组件构造器注入缺陷，生产仅补 `@Autowired`，测试改为真实 Spring 装配；修复前1项失败、修复后相关8/8。修复后AI回归再次通过，真实Redis/Qdrant专用门161/161、资源残留0；不同门禁存在重复用例，不累加成独立总数。
- 五个真实应用已启动。合成TXT文档HTTP链8/8、document-service重启后内容持久化1/1、会话生命周期8/8、AI服务重启后会话及USER/ASSISTANT错误提示恢复3/3。四个模型明确禁用，异步任务外层完成但业务结果为error，保存的是禁用提示，不算正常AI回答。
- 前端已去掉分析/关键词/索引及模型列表的伪成功兜底，并保护文档读取失败时的正文与保存入口。真实浏览器发现首跳路由未就绪即挂载助理引发重复页面，已通过等待路由就绪及移除额外协作挂载修复；浏览器复验login/dashboard/editor/paper均为单实例。
- 前端既有Memory42项、异步2项通过；新增状态12项及真实路由启动1项通过，合计57项。10/14最终重跑曾出现启动测试被浅挂载全局stub干扰而失败，主验收发现日志与旧报告不一致后要求修正：10/15改为两个独立Vitest进程，正序12→1与反序1→12均通过，保留原失败日志且未弱化断言。最终生产构建通过，既有大chunk警告保留。
- 实际Edge headless浏览器使用Vite开发服务器及真实HTTP，完成登录、列表、TXT上传解析、编辑保存刷新、重新登录读取、会话新建/指针恢复/归档恢复，以及读取失败禁用保存等检查。分段接口HTTP200/business403只证明前端错误反馈正确，不算RAG通过；生产静态托管未验证。
- 环境、启动、停止、迁移、分项结果与日志位置见 `docs/baseline/runtime-2026-10-14.md`、`regression-2026-10-14.md`、`frontend-validation.md`。真实凭据、日志和截图均放 `.local` 忽略路径，不提交。
- 后端、运行资产与前端已冻结，主协调完成差异和原始证据复核，形成本地业务候选 `5cdcd0fcde90325d61aa0e0b3ee278a3944eb2af`。在该提交实际执行原M07 Integrated入口，退出0，187/187（资产16+Java129+前端42），失败/错误/跳过均0；异步2/2与生产构建另外通过。运行前后HEAD一致、dirtyBefore/dirtyAfter均为空。
- 最终M07机器状态仍为 `AUTO_VERIFIED / MANUAL_PENDING`，其自身使用确定性环境，不篡改该状态来代替全部真实模型人工验收。额外真实依赖和浏览器证据单独记录。机器报告/调用元数据/日志在 `.local/baseline-evidence/m07-2026-10-15*`。
- 主协调结论：用户约定的“无需外部模型本地基线”通过；完整AI链路、生产静态托管、全部格式、生产容量/容灾不在结论内。分段business403、旧“AI已就绪”标记语义仍需下一阶段处理。最终汇总见 `docs/baseline/RESULT-2026-10-15.md`；main仍为56e9ceb，未推送。

## 2026-10-13 核心能力整合候选

- 本轮目标：先统一已有 MCP、Qdrant、Memory V1 成果作为后续验证基线；本轮仅建立本地候选和记录状态，不开展新增功能开发。
- 本地仓库：`D:\OmniDoc`；候选分支：`integration/core-baseline`；来源为 `origin/docs/bootstrap-project-context` 的 `09974f73c19989cbca0f2bc86bf6b75d18d77944`，建立时相对 `main` 领先 49 个提交。
- 已通过 Git 祖先关系检查确认候选完整包含 `milestone/mcp-search`（`99ed70c`）、`milestone/qdrant`（`00548c8`）、`milestone/memory-v1`（`d289cf0`）。三者逐层包含，无需重复合并或挑选提交。
- 候选来源相对 Memory V1 仅多出 `docs/PROGRESS.md` 的历史调查与发布范围记录；本轮亦仅追加本节，业务代码、依赖和配置与 Memory V1 一致。
- 本地 `main` 与 `origin/main` 保持 `56e9cebc9c63882eb847a2baea34991fc509e5df`；现有远程跟踪分支和里程碑引用均保留，本轮未推送远端。
- Review M0、其后续修复和验收分支未纳入本轮候选。历史 B 验收失败和旧机器未提交的 B-FIX3 文件仍需后续核对，不能视为已解决或已恢复。
- 文档口径说明：`docs/OMNIDOC_CONTEXT.md` 的“需求冻结/运行基线验证”及下方旧记录中的“进入主分支”属于历史阶段描述，不代表 Git `main` 已包含这些功能。当前准确状态为“核心能力候选已建立，当前机器回归与运行验证待执行”。旧报告的通过结果保留为历史证据，不作为本机新验收结果。
- 本轮检查：`git status --short --branch`、`git worktree list --porcelain`、`git for-each-ref`、`git merge-base --is-ancestor`、`git rev-list --left-right --count`、排除进展文档后的 `git diff --exit-code`；已确认功能祖先全部包含、Review M0 未包含、业务代码与 Memory V1 无差异。
- 本轮未运行构建、业务测试、基础设施或端到端流程，未验证本机服务可用性，也未更新 `main`。
- 下一步：在此候选上重跑已有自动回归与前端构建，验证登录、文件上传、RAG、MCP、会话保存与恢复；按实际结果记录问题和修复，验证通过后再更新 `main`。Review M0 单独收尾和验收。

## 2026-09-17 跨分支状态核对（调查记录）

本节记录本地分支与 GitHub 的实际差异，不表示后续实现已合入本 checkout。下方原状态快照属于本分支的 Memory V1 历史状态；Review M0 的后续进展需结合对应分支的交接记录阅读。

- 调查开始时，当前 `docs/bootstrap-project-context` 工作区干净，HEAD 为 `d289cf03fe57e81f78cab9ece9e2e47d84bf1474`。实时 `git ls-remote --heads origin` 确认 GitHub 同名分支和 `milestone/memory-v1` 均指向同一 SHA；本地与已推送版本没有代码差异。
- GitHub 当前仅有 `main`、`docs/bootstrap-project-context`、`milestone/mcp-search`、`milestone/qdrant`、`milestone/memory-v1` 五个分支；`git ls-remote --tags origin` 无输出。远端 `main` 仍为 `56e9ceb`，比当前分支少 48 个提交。当前远端公开给本次 Git 连接的 refs 不包含 Review M0 成果；不能据此推断曾被删除的远端历史。
- 本地 `milestone/review-m0` 为 `fa7f1d8`，比当前已推送基线多 25 个提交，差异为 323 个文件、38064 行新增、8 行删除。其 R3 交接记录为 `AUTO_VERIFIED / MANUAL_PENDING`，统一 Integrated 门 41/41（资产 26、生产目标 15）；这是历史验收记录，本次未重跑。
- M0 已实现后端案例、清单及确认快照、材料与 ZIP、逻辑命名与副本下载、材料齐全性/命名审查、证据报告、过期/增量重审、幂等/恢复/取消和 30 个结构化 HTTP 端点。M0 不覆盖完整奖学金 V1 的资格、填写规范、跨材料一致性、成果支持性或内容质量建议；P-09 页面仍未实现，P-08 保持 LOCAL_ONLY。
- 后续已提交产品候选为 `fix/review-m0-b-fix2-integrity-header-r1` 的 `1da749f`，比 GitHub 的 `d289cf0` 多 46 个提交，408 个文件变化（45563 行新增、270 行删除）。变化分布为 ai-service 326、common 1、docs 45、file-service 30、scripts 6；web 无变化。比 M0 里程碑新增 MySQL 8 迁移、应用启动、并发幂等、数据库故障有界响应、JWT 401、日志及下载完整性等修复。
- `validation/review-m0-r4-a-r4` 的 `edf10ac` 记录真实 MySQL 8/原生服务 A 验收通过；该结论只对应产品候选 `a8e3440`，不能当作后续候选完整 A 重验通过。
- 最新已提交 B 验收记录位于 `validation/review-m0-r4-b-r3-01a06793` 的 `5e04a93`：候选 `1da749f` 的 Probe P1-P6 通过，P7 因 file-service MyBatis 输出文件摘要及内部对象元数据失败（125 项通过后 1 项失败）；Formal B1-B8 未运行，C/D 未启动。不能宣称真实全链路或人工验收完成。
- 本地 `fix/review-m0-b-fix3-file-mybatis-runtime-logging-r1` 仍停在 `1da749f`，另有 7 个未提交文件（4 个已跟踪修改、3 个新增），涉及 MyBatis 日志关闭、运行时配置、测试及专项脚本。其现存 2026-09-04 Surefire XML 合计 33 tests、0 failures/errors/skipped；未找到专项 result/report/summary JSON 或已提交 B-FIX3 交接，不能确认真实 B 验收已转通过。
- 本次实际检查：`git status --short --branch`、`git remote -v`、`git branch -vv`、`git ls-remote --heads/--tags origin`、`git worktree list --porcelain`、各 Review worktree 的 `git status --porcelain`、`git show`/`git log`、`git rev-list --left-right --count`、`git diff --shortstat/--name-only`、Controller 路由和前端入口静态检索、现有交接文档和 Surefire XML 核对。未启动服务、未执行业务测试、未切换/合并/提交/推送分支。本次仅追加本节调查记录。
- 下一步：完成 B-FIX3 的修复验证与提交，在新候选上重跑 B Probe/Formal，再完成其余真实链路及页面验收；按明确候选整理集成与推送，不能把分散 worktree 或历史局部通过视为统一完成版本。

## 2026-09-17 GitHub 推送准备

- 用户已要求先推送当前成果。本次选择当前文档分支、`milestone/review-m0`、`fix/review-m0-b-fix2-integrity-header-r1`、`validation/review-m0-r4-a-r4` 和 `validation/review-m0-r4-b-r3-01a06793`，保留实现候选与验收证据的分支边界。
- B-FIX3 工作区的 7 个未提交文件仍是未完成修复，不纳入此次已提交版本推送；不移动里程碑，不合并 `main`。
- 本次为提交及推送已有成果，不重跑业务测试。推送是否成功以执行后的远端 SHA 核对及任务回执为准；前节远端缺少 Review M0 的描述是推送前调查快照。

## 状态快照

- **更新日期**：2026-08-11
- **当前阶段**：Memory V1 概念边界已冻结，M01-M07 已集成且最终确定性门禁 `AUTO_VERIFIED`；真实浏览器与外部依赖处于 `MANUAL_PENDING`
- **首个专业场景**：奖学金申报材料审查 V1
- **下一阶段**：由用户按 Memory V1 人工清单验证真实浏览器/JWT、MySQL、Redis 和模型；奖学金审查 Agent 仍保持独立规划，不纳入本轮 Memory 实现

## 已确认

以下内容为已经确认的产品或需求结论，不等同于功能运行验证：

- OmniDoc 定位为“通用文档智能工作台 + 多场景文档审查 Agent 平台”。
- 保留文件管理、文档解析、编辑、版本、RAG、通用 Agent、工具调用、审批、Trace 和 PPT 等能力方向。
- 首个专业场景及 V1 目标用户已经确定。
- V1 输入材料、七类输出、证据规则和人工确认原则已经冻结。
- V1 不做获奖概率预测、印章真伪识别、自动提交和自动覆盖原始文件。
- 项目上下文、V1 需求和仓库协作约定已经形成文档基线。
- MCP 搜索接入的 MVP 范围、架构、Java 契约、错误语义、任务边界和验收标准已经冻结。
- Qdrant 接入采用保留 Redis + JVM HNSW 基线、增加 `baseline`/`shadow`/`qdrant` 三种模式的方案；公共 Java 契约、配置名称、任务边界和验收规则已经冻结。
- QV01-QV04 已按冻结顺序集成；生产装配、双后端、Shadow、可恢复迁移、scope 过滤和回滚边界已经进入主分支，专用自动验收为 159/159。
- Memory 长期保留 `CONVERSATION`、`WORKSPACE`、`USER` 三级作用域；Memory V1 只实现 Conversation Memory，另外两级只预留契约。
- Memory V1 冻结使用结构化消息、Structured Summary、近期原始消息、token 预算和统一上下文组装器；Runtime State 明确不属于长期记忆。
- Conversation 原始消息默认长期保留；Memory V1 不使用 Qdrant，未来语义记忆通过独立 `MemoryVectorIndex` 和逻辑语料空间接入。
- Memory V1 使用 MySQL 作为 Conversation/Message/Summary 事实主库，Redis 仅用于缓存、Runtime State 和必要锁；并行开发波次、文件所有权、自动门禁和人工清单已经冻结。
- `AgentExecutionServiceTest` 与 `AgentControllerTest` 在当前工作树上共 32 个测试通过，可作为 MCP Agent 集成的直接回归基线；加入 T00 契约测试后相关测试为 35/35 通过。

## 尚未验证

运行基线验证仍未完整完成，因此不得将以下事项描述为已验证：

- 各服务在目标环境中的启动、依赖和配置状态；
- 文件、解析、编辑、版本、RAG、Agent、工具调用、审批、Trace 和 PPT 能力的实际可用性；
- `AgentExecutionService`、`AgentToolRegistry`、RAG 与文档能力对奖学金审查场景的接口契合度；
- 从材料输入到审查结果、证据追溯和人工确认的端到端链路；
- 奖学金审查 V1 的实现完成度、测试覆盖、性能与生产可用性。
- 真实模型、Tavily MCP、完整服务启动和端到端外部搜索链路的可用性。
- Qdrant 用户人工验收、真实远程 Embedding/模型驱动的 RAG 闭环，以及生产 TLS、鉴权、容量、高可用、备份和灾备。
- Memory V1 已完成最终确定性集成验收，但真实 MySQL/Redis/模型/浏览器联调和异步 job 提交后刷新恢复轮询仍未完成，最终状态不得写为 `HUMAN_ACCEPTED`。
- Workspace Memory、User Memory、记忆候选、跨会话语义召回和记忆管理 UI 不属于 Memory V1，尚未实现。

## 后续运行基线验证

建议按以下顺序推进并记录实际结果：

1. 确认本地运行所需的基础设施、配置和依赖；
2. 记录各服务的启动命令、成功条件与实际结果；
3. 逐项盘点需保留的通用能力及其入口、接口和验证状态；
4. 验证 `AgentExecutionService`、`AgentToolRegistry`、RAG 和文档能力的最小复用路径；
5. 基于验证结果识别奖学金审查 V1 的最小实现缺口与风险；
6. 在进入实现前更新本文件，不把静态代码存在等同于运行可用。

## MCP 搜索接入 T00

### 已完成

- 已完整读取仓库协作约定、项目上下文、奖学金审查 V1 需求和本进展文档；
- 已修复 Git `safe.directory` 阻塞，仅添加仓库精确路径；
- 已记录 T00 起始基线：分支 `docs/bootstrap-project-context`，HEAD `8427f59`，暂存区为空；
- 已盘点进入 T00 前的工作树：18 个已跟踪修改、16 个未跟踪文件；
- 已按用户选择的方案 A 审查原有改动与敏感信息，并形成独立 checkpoint 提交 `51caddf`；
- 已建立 `docs/mcp-search/` 目标、架构、契约、任务、集成、自动测试和人工验收文档；
- 已新增不依赖具体 MCP SDK 的 `mcp.contract` Java 编译边界；
- 已冻结使用 MCP Java SDK 2.0.0、Jackson 2、Sync Client 和 Streamable HTTP 的方案；
- 已明确不升级现有 Spring AI，不启用非工具 MCP 能力，MVP 只暴露白名单 Search。

### 验证结果

- 默认完整测试：53 个测试中 52 个通过、1 个错误；错误原因是缺少 `DASHSCOPE_API_KEY` 导致 `AllModelsTest` 上下文加载失败；
- 测试进程使用非真实占位配置后：53/53 通过；其中四个真实模型调用均认证失败，实际模型连通为 0/4，该结果不能证明模型服务可用；
- MCP 直接相关回归：`AgentExecutionServiceTest` + `AgentControllerTest` 共 32/32 通过；
- T00 契约加入后的直接回归：上述测试加 `McpContractTest` 共 35/35 通过；
- 端到端服务启动：未执行；
- 真实 Tavily MCP：未执行。

### 基线决策

用户已选择方案 A。进入 T00 前的 34 个文件已经审查并保存到 checkpoint `51caddf`；T00 契约和文档作为后续独立提交，不再存在工作树保存策略阻塞。T01-T04 已于 2026-08-09 从共同基线 `61266db` 创建独立分支、工作树和 Codex 任务并开始执行。

## MCP 搜索并行任务进展

### T00：基线、契约与任务拆分

- 状态：`HANDOFF_READY`
- 分支：`docs/bootstrap-project-context`
- 验证：直接相关测试 35/35 通过；真实 Tavily MCP 未验证

### T01：MCP 连接与协议层

- 状态：`MERGED`
- 分支：`feature/mcp-search-transport`
- 提交：`04da0a4`
- Codex 任务：`019fe237-e23b-7c60-a394-658c32ea49d5`
- 工作树：`../.codex-worktrees/mcp-search-t01`
- 实现：已接入 MCP Java SDK 2.0.0 的 `mcp-core` 与 `mcp-json-jackson2`，使用 Sync Client 和 JDK HttpClient Streamable HTTP；已实现 Bearer Header、初始化、显式分页 `tools/list`、allowlist、`tools/call`、连接/请求超时、fail-open、优雅关闭、标准错误映射、结果大小限制和密钥脱敏；默认关闭时不创建远程 Client
- 验证：T01 专项测试 15/15 通过；加入 `AgentExecutionServiceTest`、`AgentControllerTest` 与 `McpContractTest` 后直接相关回归 50/50 通过；MCP 2.0.0 依赖与主代码编译通过
- 未验证：无真实 Tavily Key，未执行真实 Tavily MCP、完整 `ai-service` 外部基础设施启动或 Agent 端到端搜索；这些内容留待集成与验收任务
- 风险：Spring Boot 3.2 依赖管理最终解析 Jackson 2.15.3、Reactor 3.6.0，而 MCP 2.0.0 发布 POM 声明 Jackson 2.20.1、Reactor 3.7.0；当前专项协议测试通过，未升级现有 Spring AI/Boot 依赖栈，仍需在后续完整集成中关注版本兼容

### T02：动态工具目录与 Schema

- 状态：`MERGED`
- 分支：`feature/mcp-search-tool-catalog`
- 提交：`9128188`
- Codex 任务：`019fe238-0377-7b12-aeef-63bb25114509`
- 工作树：`../.codex-worktrees/mcp-search-t02`
- 实现：已完成 MCP allowlist 二次过滤、`mcp.<serverId>.<remote-name>` 命名、完整原始 Schema 与来源元数据保留、支持类型/required/enum/数值范围/pattern 的 Planner 映射、本地固定只读低风险策略，以及按 Server 原子刷新、失败保留旧版、冲突保护和隔离移除；原生工具行为保持兼容。
- 验证：T02 新增测试 8/8 通过；加入 `AgentExecutionServiceTest`、`AgentControllerTest` 和 `McpContractTest` 的直接回归共 43/43 通过。
- 未验证：真实 MCP SDK/Transport、Tavily 远程调用、完整服务启动、T01-T03 联调和高并发刷新压力；union/组合 Schema 与部分高级约束仅保留原始 Schema，未进入简化参数字段。
- 下一步：主协调任务按 T02 -> T01 -> T03 顺序集成，并由 T04 执行模拟 Server 与完整自动验收。

### T03：Agent 路由、调用与结果合成

- 状态：`MERGED`
- 分支：`feature/mcp-search-agent-routing`
- 提交：`4e0f3aa`
- Codex 任务：`019fe238-24bf-7f50-a220-fb0267461265`
- 工作树：`../.codex-worktrees/mcp-search-t03`
- 进展：已完成 MCP/RAG/禁网路由约束、`McpToolGateway` 分发、action-required 复用、structured/text 结果归一化、上下文大小限制、实际来源引用、MCP Trace 与失败语义；原生工具分发保持不变
- 验证：T03 新增测试 10/10；`AgentExecutionServiceTest` + `AgentControllerTest` 原有基线 32/32；加冻结契约测试后直接相关组合回归 45/45
- 未验证：T01/T02 合并联调、完整服务启动、真实模型、真实 Tavily MCP 与端到端远程搜索
- 下一步：由主协调任务按 T02、T01、T03 顺序集成，并由 T04 执行独立验收

### T04：独立验证与交付

- 状态：`AUTO_VERIFIED`
- 分支：`test/mcp-search-acceptance`
- 提交：`74bcdd6`
- Codex 任务：`019fe238-3e05-7540-aa00-f66dc4cfcdaf`
- 工作树：`../.codex-worktrees/mcp-search-t04`
- 基线：`61266dbbe268958c1e4d7ccbea9ccc5f15220ad8`
- 已完成：独立 Streamable HTTP 模拟 MCP Server、9 个 fixture 自检、统一验收入口、集成门禁、静态密钥泄漏检查和人工验收入口
- 独立分支验证：44/44 通过（fixture 9、MCP 契约 3、Agent/Controller 回归 32）；非真实占位配置下全量 65/65 通过但远程模型成功 0/4；静态检查 570 个 Git 可见文本文件无命中
- 独立分支门禁：`-Integrated` 在当时 T01-T03 未集成时按设计退出 1 并报告 `PENDING_INTEGRATION`，未把缺失实现计作通过
- 真实 Tavily：当前进程无 `TAVILY_API_KEY`，未连接、未调用、未验证
- 最终集成：T02、T01、T03、T04 已按顺序合并；集成连接器提交为 `ff9b619`
- 最终验证：T04 集成模式 78/78 通过，敏感信息静态检查扫描 588 个文本文件无命中；非真实占位配置下完整 `ai-service` 回归 99/99 通过，四个远程模型实际成功仍为 0/4

## MCP 搜索最终自动验收

- T01 合并提交：`01240a6`；
- T02 合并提交：`20e6e8d`；
- T03 合并提交：`bcfebe3`；
- T04 合并提交：`4670d2c`；
- 集成修复提交：`ff9b619`，负责把 T01 启动发现结果发布到 T02 动态工具目录；
- 模拟 Server 跨层链路：真实 SDK Transport → 分页发现 → allowlist → Schema 映射 → 动态注册 → `tools/call` 通过；
- T04 `-Integrated`：78/78 通过，Failures 0、Errors 0、Skipped 0；
- 完整 `ai-service`：99/99 通过，Failures 0、Errors 0、Skipped 0；
- 静态泄漏检查：588 个 Git 可见文本文件无命中；
- 真实 Tavily、真实模型、完整服务外部基础设施启动和用户人工路由验收仍未执行。

## Qdrant 向量后端 QV00（历史基线）

### 已完成

- 已建立 `docs/qdrant/` 目标、架构、冻结契约、任务看板、集成日志、自动测试和人工验收文档；
- 已新增供应商无关的 `rag.vector.contract` Java 边界，冻结后端、模式、租户作用域、Embedding 规格、迁移分页和错误语义；
- 已在 `application.yml` 与 `application-local.yml` 增加默认 `baseline` 的 Qdrant 配置骨架，当前运行路径尚未使用；
- 已新增确定性契约测试和现有 Redis + JVM HNSW 特征测试；
- 已冻结 QV01-QV04 的职责、文件所有权、集成顺序和验收门禁。

### 基线发现

- 相同逻辑 ID 覆盖写入时，当前 JVM HNSW 可能仍返回旧向量，后续删除也可能残留旧版本命中；
- QV00 未越界修改生产实现，两项回归测试已明确禁用并分配给 QV01 修复、启用；
- 该缺陷修复前，不得把现有 baseline 的幂等 upsert 和删除语义描述为已验证。

### 尚未验证

- QV01-QV04 已创建并进入并行开发，但尚未形成交接提交或集成；
- 现有 `VectorStore` 尚未接入新契约；
- Qdrant SDK、Docker、Collection、alias、payload index、Shadow 和迁移均未实现；
- 未连接真实 Redis、Qdrant 或 Embedding 服务；
- 未完成 Qdrant 自动集成验收或用户人工验收。

### QV00 验证结果

- QV00 专项：10 个测试，8 个通过、0 个失败、0 个错误、2 个按已知缺陷跳过；
- 完整 `ai-service`：109 个测试，107 个通过、0 个失败、0 个错误、2 个按已知缺陷跳过，`BUILD SUCCESS`；
- 完整回归使用非真实占位配置，远程模型实际成功仍为 0/4；
- QV00 未创建 Qdrant Client，未连接真实 Redis、RabbitMQ、Embedding 或 Qdrant；
- `git diff --cached --check` 通过，静态敏感信息检查扫描 621 个文本文件，无命中。

## Qdrant QV01-QV04 最终集成

### 提交与状态

- 共同基线：`3ec7a0fb629aac3d526ac03058b577b71f921a2b`；
- QV01 来源 `93fb4f8`，合入提交 `f99d04a`，状态 `AUTO_VERIFIED`；
- QV02 来源 `6e62e0d`，合入提交 `d9f2efb`，状态 `AUTO_VERIFIED`；
- QV03 来源 `3eb4efc`，合入提交 `b2c73d0`，状态 `AUTO_VERIFIED`；
- QV04 来源 `695ee73`，合入提交 `6e71324`，状态 `AUTO_VERIFIED`；
- 协调集成提交：`3457737`；
- 总体状态：`AUTO_VERIFIED / MANUAL_PENDING`。

### 已完成

- 当前 Redis + JVM HNSW 被封装为 baseline 后端，并修复相同 ID 覆盖和删除后旧 HNSW 命中；
- 接入官方 Qdrant Java Client、Collection/alias、payload index、服务端 scope 过滤、幂等写入和删除；
- 实现 `baseline`、`shadow`、`qdrant` 三种生产装配：baseline 不创建 Qdrant Client，qdrant 不构造/扫描本地 HNSW；
- 实现 Shadow 对比、失败隔离、指标，以及只在 shadow 模式提供的内部迁移服务；
- 本地后端支持稳定游标分页导出，迁移支持 dry-run、失败 checkpoint、恢复、数量核对和幂等重跑，不自动删除 Redis 源数据；
- RAG 检索在向量查询前传入 `userId + knowledgeBaseId`，文档删除改为一次带 scope 的后端删除；
- QV04 真实 provider 使用隔离 Redis DB 15、两个独立 Qdrant Client 和真实 Qdrant 重启；测试资源严格限制为 `qv04_test_*` 并在结束后清理。

### 验证结果

- QV04 专用集成入口：资产/Agent/RAG/MCP 110/110，真实双后端 49/49，合计 159/159，Failures 0、Errors 0、Skipped 0；
- 硬门禁：Qdrant Recall@5 1.0，baseline Recall@5 1.0，tenant leakage 0，delete residue 0，migration count difference 0；
- 迁移：源 60、成功 60、失败 0、目标 60，dry-run 写入 0，重跑后仍为 60，Redis 源未删除；
- 完整 `ai-service`：178 个测试，Failures 0、Errors 0、Skipped 3，`BUILD SUCCESS`；三个跳过项均为普通回归中隔离的真实基础设施测试，并已在专用入口执行通过；
- 配置层级回归：5/5 通过，确认 `ai.vector.migration.*` 未误嵌套到 `shadow`；
- 敏感信息检查：扫描 695 个 Git 可见文本文件，无命中；
- 清理复核：Qdrant `qv04_test_*` 资源 0，Redis DB 15 的 `vector:qv04-*` 和 `metadata:qv04-*` 残留均为 0；
- 完整回归使用非真实模型占位配置，四个远程模型实际成功仍为 0/4。

### 剩余边界

- 用户尚未执行 `docs/qdrant/06-HUMAN_ACCEPTANCE.md`，不得标记 `HUMAN_ACCEPTED`；
- 未使用真实远程 Embedding、真实用户文档或真实模型验证完整 RAG；
- 本机单节点合成性能不是生产容量、TLS/鉴权、网络隔离、HA、备份、灾备或长期压力证据；
- Qdrant 验收结束后已停止本次临时启动的 Qdrant 服务，Redis 保持任务开始前的既有运行状态。

## Memory V1 概念冻结与任务设计

### 已完成

- 新增 `docs/memory/00-MEMORY_V1_SPEC.md`，冻结 Memory V1 目标、三级作用域长期架构、Conversation-only 交付范围、结构化摘要和行为不变量；
- 明确作用域与记忆类型为两个正交维度，Runtime State 独立于记忆；
- 冻结 Conversation Message、Structured Conversation Summary、SourceRef、状态枚举、上下文组装、摘要更新、长期保留和删除契约；
- 明确 Workspace/User Memory 仅预留作用域，不参与 V1 写入、检索或模型上下文；
- 明确 Memory V1 不依赖 Qdrant，未来语义记忆复用向量基础设施但使用独立 `MemoryVectorIndex` 与逻辑语料空间；
- 新增 `docs/memory/01-TASK_BOARD.md`，拆分 M01-M07 实现与验收任务。
- 新增 `docs/memory/02-PARALLEL_DELIVERY_PLAN.md`，冻结多 Codex 分波并行、文件所有权、共享热点和集成顺序；
- 新增 `docs/memory/02-API_AND_STORAGE_CONTRACT.md`，冻结 MySQL/Spring JDBC、幂等消息、Redis 兼容、Runtime TTL、Summary digest、token 默认预算、结构化 API 和前端会话导航；
- 新增 `docs/memory/03-AUTO_ACCEPTANCE.md` 与 `04-HUMAN_ACCEPTANCE.md`，冻结 P0/P1 自动门禁、机器报告和十项人工验收场景；
- M01 已启动后端、前端和验收三个只读并行审计，尚未修改生产代码。

### 验证状态

- 本阶段仅修改设计文档与进展记录，未修改业务代码；
- M01 后端只读审计已运行 `AgentExecutionServiceTest + AgentControllerTest`，结果 32/32 通过；
- M01 前端只读审计已运行现有 `asyncJob.test.mjs`，结果 2/2 通过；
- 持久化依赖树检查构建成功，但确认 `ai-service` 当前没有 JDBC、MyBatis、MySQL Driver、Flyway 或 Liquibase 依赖；
- 未运行真实模型、Redis、MySQL、Qdrant、浏览器端到端或完整服务；
- Memory V1 生产实现状态仍为 `IMPLEMENTATION_NOT_STARTED`；
- M01 状态为 `BASELINE_VERIFIED`；M02-M07 目标能力仍未实现，不能把 M01 资产通过描述为 Memory V1 自动验收通过。

### M01 并行审计发现

- 当前 `conv:*` 是 24 小时 TTL、最多 100 个字符串条目的 Redis Hash，消息追加为整列表读改写，存在并发覆盖和消息对截断风险；
- 当前 `ctx:*` 接受任意字段且无 TTL，Conversation 删除不会清理它；
- Agent 保存历史但不读取历史，Planner、Direct Answer、Reflection、完成度判断和 Final Synthesis 各自拼装不同输入；
- 全局助理刷新后只保留一个未按用户隔离的 ID，不恢复消息，创建失败会伪造 `temp_*`；
- 编辑器刷新会创建新 Conversation，“新话题”只清 UI 且继续复用旧 ID，创建失败回退 `default_conv`；
- 当前前端没有通用组件测试框架，只有 Node 内置 API 测试；
- 当前可执行范围仅为 M01 测试、fixture 与验收资产建设，持久化、摘要、统一上下文和人工验收均仍不可执行。

### M01-BE 后端行为基线

- 状态：`HANDOFF_READY / PRODUCTION_CODE_UNCHANGED`；分支 `test/memory-m01-backend`，基线 `37f81f7`；
- 新增 16 个通过型后端行为刻画测试，覆盖 Redis Conversation/Context 生命周期、条数截断、长消息、并发读改写风险、Agent 不读取历史、各阶段独立 Prompt、旧 Context 回流和兼容 Controller 入口；
- 新增 16 个 `PENDING_IMPLEMENTATION` 目标契约，分别归属 M02/M03/M04/M05 各 4 个，当前均以逐项说明原因的 `@Disabled` 表示，未计作目标通过；
- 新增专项结果：32 个测试被发现，16 个通过、16 个目标跳过、Failures 0、Errors 0，`BUILD SUCCESS`；
- 新增专项加 `AgentExecutionServiceTest + AgentControllerTest`：64 个测试被发现，48 个通过、16 个目标跳过、Failures 0、Errors 0，`BUILD SUCCESS`；原有 Agent/Controller 仍为 32/32；
- 未连接真实 Redis、MySQL、模型、MCP 或 Qdrant，未运行完整服务和端到端；确定性并发夹具证明读改写风险，不代表真实 Redis 压力或生产丢失率；
- 精确命令、测试矩阵和后续启用规则见 `docs/memory/handoffs/M01-BE.md`。M01-FE/M01-AC 已由协调任务集成并复跑，M01 总体进入 `BASELINE_VERIFIED`。

### M01-FE 前端行为基线（`test/memory-m01-frontend`）

- 状态：`HANDOFF_READY / CURRENT_BEHAVIOR_VERIFIED`；基线提交为 `37f81f7`；
- 已增加 Vitest、Vue Test Utils、jsdom、独立 Memory 基线脚本和 7 个组件/composable 行为测试；未修改生产 Vue/JS 行为；
- 已确定性复现：刷新只恢复 ID、不恢复消息；reset 仅清本地；全局入口不拉历史；创建失败使用 `temp_*`；编辑器新话题复用旧 ID；创建失败使用 `default_conv`；两个入口活跃会话状态分裂；
- 验证：现有 Node 测试 2/2 通过，M01-FE Memory 基线 7/7 通过，Vite 生产构建通过；未运行真实浏览器、后端、模型、MySQL 或 Redis；
- 构建保留主 chunk 超过 500 kB 的警告；依赖安装审计报告 12 个问题（3 moderate、7 high、2 critical），未在本基线任务中执行破坏性依赖升级；
- 交接：`docs/memory/handoffs/M01-FE.md`。这些测试证明当前缺口，M06 修复时应以目标契约测试替换或反转缺陷断言。

### M01-AC 验收资产

- 已新增 Memory V1 确定性合成 fixture、Structured Summary JSON Schema、机器报告 JSON Schema 及 14 项只验证资产与契约的测试；
- 已新增 `scripts/memory/run-m07-acceptance.cmd` 统一入口、集成就绪检查和敏感数据/报告正文边界检查；
- 默认入口 14/14 测试通过并生成 `ASSET_VERIFIED` 报告；该状态只证明 M01 验收资产可用，不证明生产 Memory V1 行为；
- `-Integrated` 在当前生产实现与 M07 集成门禁缺失时明确生成 `PENDING_INTEGRATION` 报告并以非零状态结束；
- 机器报告只含元数据、计数、真假值边界与未验证原因，不含会话、摘要、文档、Prompt、选中文本、工具结果或凭据正文；
- 本任务未修改生产代码，未运行真实模型、MySQL、Redis、浏览器、远程 Embedding 或 Qdrant，不得声称 `AUTO_VERIFIED`；
- 详细交接见 `docs/memory/handoffs/M01-AC.md`。

### M01 最终集成验证

- 集成顺序：M01-BE `e699339` -> M01-FE `af9df81` -> M01-AC `e20de13`；协调分支对应提交为 `672fc54`、`3539fb6`、`b67dcea`；
- 后端组合：64 个测试被发现，48 个通过、16 个 M02-M05 目标契约按计划跳过、Failures 0、Errors 0；
- 前端：现有 Node 测试 2/2、Memory Vitest 7/7、Vite 生产构建通过；主 chunk 超过 500 kB 的既有警告仍在；
- 验收资产：协调修正“无稳定目标时 `goal=null`”契约后 14/14 通过，敏感内容检查扫描 725 个 Git 可见文本文件无命中；
- 默认 M07 入口退出 0，报告为 `ASSET_VERIFIED / PENDING_INTEGRATION`；`-Integrated` 退出非零并准确列出 M02-M07 生产能力缺失，没有误报 `AUTO_VERIFIED`；
- 主工作树安装前端测试依赖时首次 `npm ci` 因正在占用的 `esbuild.exe` 返回 EPERM；改用不改锁文件的 `npm install --ignore-scripts` 后测试和构建通过，残留仅为被占用二进制临时目录的清理警告；
- 未连接真实 MySQL、Redis、模型、远程 Embedding、浏览器或完整服务；M01 只证明基线和验收资产，不证明 Memory V1 生产能力。

### M06-A 前端结构化 API 与状态骨架（`feature/memory-m06a-frontend-contract`）

- 状态：`HANDOFF_READY / FRONTEND_CONTRACT_VERIFIED / INTEGRATION_PENDING`；起始基线 `ac1033b`；完整 M06 仍为 `PENDING`；
- 已新增 `/ai/conversations` 七个端点的统一前端 API、Conversation/Public Message/Page/Delete DTO、稳定错误规范化和公开工具事件 metadata 白名单；客户端不发送所有者 `userId`；
- 已新增 `useConversationSession`：活跃指针与草稿按认证 `userId + global-assistant/editor` 隔离，创建成功后才切换，失败保留旧指针和草稿，UUID `clientRequestId` 在重试与标签页刷新中复用；明确拒绝 `temp_*` 和 `default_conv`；
- 专项契约 14/14、M01 前端基线 7/7、现有 Node API 测试 2/2、Vite 生产构建均通过；禁止的旧 UI、`useGlobalAi.js` 和后端文件差异为 0；
- 未连接真实结构化 Controller、MySQL、Redis、鉴权用户或浏览器端到端；新骨架尚未接管现有 UI，不能宣称历史恢复、新对话、归档或删除 UX 已实现；
- `npm ci` 仍报告 12 个依赖审计问题，构建仍有主 chunk 超过 500 kB 的警告；详细交接见 `docs/memory/handoffs/M06-A.md`。

### M07-A Wave 1 阶段验收骨架

- 已新增 M02 持久化阶段门禁：实现缺失时生成脱敏 `PENDING_INTEGRATION` 报告并非零退出；实现存在时固定运行 M02 目标、持久化专项及 Agent/Controller 回归，并要求四个 M02 目标 skipped=0；
- 已新增 M06-A 前端契约阶段门禁：独立检查 Conversation API/状态模块和 `test:memory-contract`，同时回归 Agent/Controller；该门禁不要求后端结构化 API，不代表 M06-B/C 完成；
- Report Schema 已区分 `FINAL_ACCEPTANCE` 与 `STAGE_GATE`；阶段报告强制 `finalAcceptance=false`、`manualStatus=NOT_READY` 且不能输出 `AUTO_VERIFIED`；
- 两个阶段门禁的资产自检均为 5/5 通过、实际退出码 0；当前基线的 Integrated 模式均准确 `PENDING_INTEGRATION` 并实际非零退出；
- 原 M07 资产入口扩展后为 16/16 通过；完整 Integrated 入口仍因 M02-M07 和最终 gate 缺失而 `PENDING_INTEGRATION`，没有创建 `MemoryV1IntegratedGateTest`；
- 静态敏感数据与报告正文边界检查最终扫描 731 个 Git 可见文本文件，无命中；未运行真实 MySQL、Redis、浏览器、模型、远程 Embedding 或 Qdrant；
- 详细交接见 `docs/memory/handoffs/M07-A.md`。

### M06-A / M07-A 协调集成验证

- 协调分支合入 M06-A 与 M07-A 后，首次运行 M06-A Integrated 门禁发现脚本只识别源码字面量 `/api/ai/conversations`，未识别项目现有 `baseURL=/api` 与模块路径 `/ai/conversations` 的等价组合；已将门禁修正为接受两种冻结资源写法；
- 修正后 `run-frontend-contract-gate.cmd -Integrated` 实际通过：合计 51 项测试，Failures 0、Errors 0、Skipped 0，其中 Report Schema 5 项、前端契约 14 项、Agent/Controller 回归 32 项；
- 阶段报告为 `STAGE_GATE / M06_A / STAGE_VERIFIED`，并保持 `finalAcceptance=false`、`manualStatus=NOT_READY`；完整 M06-B/C 和最终 M07 仍为 `PENDING_INTEGRATION`；
- 敏感数据与报告正文边界检查在协调分支扫描 738 个 Git 可见文本文件，无命中；未运行真实浏览器、MySQL、Redis 或模型。

### M02 Conversation/Message 持久化（`feature/memory-m02-persistence`）

- 状态：`HANDOFF_READY / H2_MYSQL_MODE_VERIFIED / REAL_MYSQL_PENDING`；基线 `ac1033b`；
- 已新增 Spring JDBC/MySQL Driver、Conversation/Message 领域模型、JDBC Store、版本化幂等 Schema、启动校验、稳定分页、归档/恢复/物理删除、所有者隔离和旧 Redis owner-check 后按需导入；
- sequence 分配和 Message 插入在同一事务内完成；16 个并发追加任务的 H2/MySQL-mode 用例验证 sequence 1..16 连续唯一；`clientRequestId + role + type + ordinal` 重试不重复写入；
- `ConversationManager` 已迁移为 MySQL Conversation Service 的旧 API 兼容门面；不再读写 Redis 事实列表，无认证用户参数的旧方法被明确拒绝；
- `MemoryV1ExpectedContractTest` 的四个固定名称 M02 占位已改为真实测试并全部通过；M03-M05 的 12 个占位仍按计划跳过；
- M02 专项：30 个测试被发现，18 个通过、12 个 M03-M05 目标跳过、Failures 0、Errors 0；组合 Agent/Controller 回归：69 个测试被发现，57 个通过、12 个 M03-M05 目标跳过、Failures 0、Errors 0；
- `AllModelsTest` 在 Memory 关闭、四个模型禁用和非真实占位凭据下 1/1 通过，未连接 MySQL；既有 Redis/RabbitMQ 后台连接失败日志不属于本任务新增回归；
- 未连接真实 MySQL/Redis、真实模型、浏览器或完整服务；未修改 Agent 主链、Runtime State、Summary、Context Builder、Controller 或前端；详细交接见 `docs/memory/handoffs/M02.md`。

### M02 协调集成与删除边界修正

- M02 合入后首次 Integrated 阶段门禁通过：Report Schema 5 项与集成选择 55 项均无失败/错误；四个 M02 固定目标均执行且 skipped=0，另有 12 项跳过只属于尚未实现的 M03-M05；报告为 `STAGE_GATE / M02 / STAGE_VERIFIED`，不是最终验收；
- 协调审查发现：已导入 MySQL 的旧 Redis 会话永久删除时若只删除数据库，同一 `conv:*` 可能再次触发按需导入，造成“删除后复活”；已在数据库删除前增加所有者校验后的旧源清理，旧源不可用时明确失败并保留数据库事实以便重试；
- 已新增“删除旧源后不可重新导入”和“旧源清理失败不提交可复活的数据库删除”两项回归；Legacy/Store/目标测试组合 27 项发现，15 项通过、12 项仅 M03-M05 跳过、Failures 0、Errors 0；
- M02 阶段门禁已扩展为同时运行旧 Redis 导入/删除、ConversationManager 兼容、既有 Conversation Agent/Controller 基线，防止该 P0 删除边界再次遗漏；
- 扩展后的最终 M02 Integrated 门禁发现 71 项，59 项通过、12 项仅 M03-M05 跳过、Failures 0、Errors 0；另有 Report Schema 5/5 通过，阶段报告仍为 `STAGE_VERIFIED` 且四个 M02 目标 skipped=0；
- 仍未验证真实 MySQL 8、真实 Redis 删除/导入和完整服务重启；这些边界不会被 `STAGE_VERIFIED` 冒充为已完成。

### M03-A 受控 Runtime State 核心（`feature/memory-m03-runtime-state`）

- 状态：`RUNTIME_CORE_VERIFIED / DELETION_AND_AGENT_INTEGRATION_PENDING`；起始基线 `cc4c71b`；完整 M03 仍未完成；
- 新增 typed Runtime State 服务，白名单严格限定为 `currentDocumentId/selectedText/pageContext/pendingApproval/currentToolState`，按 `conversationId + traceId` 隔离；
- 五类状态分别具有冻结生命周期、TTL 和 UTF-8 大小硬上限；配置只能收紧不能放大；一次性选区/页面上下文提供消费即删，文档定位为滑动 30 分钟，审批不超过引用有效期，工具状态最长 1 小时并提供 trace 清理；
- 新服务不接受任意 Map；Redis 值采用严格版本化 envelope，typed 审批仅保存非敏感引用，typed 工具状态不包含完整结果，并拒绝非受控 Map、额外字段和 credential/token 特征状态；
- 提供 required read、显式 optional degrade、受控 update、clear fields、clear trace 和 clear conversation；`RUNTIME_STATE_UNAVAILABLE` 区分必须失败路径，optional read 返回 `DEGRADED_UNAVAILABLE` 而不伪装正常空状态；
- 旧 `ctx:*` 只短期读取三个 bounded scalar（含 `documentId -> currentDocumentId` 兼容别名），明确忽略令牌、任意 Map、完整工具结果与文档正文，每次读取施加 10 分钟 TTL，且没有 Summary 导入路径；
- 已将 `MemoryV1ExpectedContractTest` 前两个 M03-A 门禁替换为真实断言；删除级联与 Agent/owner 两项继续 `Disabled`，分别等待 M02 删除联调和 M03-B；
- 专项加契约：最终组合回归包含的 36 个相关测试中 22 个通过、14 个其他目标契约按计划跳过、Failures 0、Errors 0；M03-A 三个专项类 20/20 且 Skipped 0；
- Agent/Controller 组合回归为 75 个测试被发现、61 个通过、14 个尚未交付目标契约按计划挂起、Failures 0、Errors 0；Agent/Controller 与既有 Conversation 行为测试 41/41 且 Skipped 0；
- 未连接真实 Redis、MySQL、模型或完整服务；`clearConversation` 尚未被 M02 永久删除编排调用，Agent 续接、审批取消、trace-conversation-user owner 校验和真实级联删除均未联调；详细交接见 `docs/memory/handoffs/M03.md`。

### M07-B M03-A Runtime Core 阶段门禁

- 已新增 `run-m03-runtime-gate.cmd/.ps1`，默认资产模式验证脚本、Report Schema 与脱敏边界；Integrated 模式检查 RuntimeState model/service、Redis persistence、字段白名单、分层 TTL、clear/consume 接口和专项测试；
- Readiness 接受独立 Store/Repository，或 Service 直接封装 `RedisTemplate` 的合理实现，不把额外分层误设为硬门槛；
- 固定运行 `MemoryV1ExpectedContractTest + *RuntimeState*Test + *ContextManager*CompatibilityTest`；两个 M03-A 目标必须 skipped=0，两个 M03-B 删除级联/所有权目标允许继续 Disabled；
- Report Schema 新增 `M03_A` 而非完整 `M03`；阶段报告即使通过也保持 `finalAcceptance=false`，并在 `readinessMissing/unverified` 明确 `M03B_DELETION_CASCADE_PENDING` 与 `M03B_OWNER_APPROVAL_PENDING`；
- M03-A 默认资产模式实际退出 0、Schema 5/5；当前基线 Integrated 实际非零退出并准确 `PENDING_INTEGRATION`，尚未执行生产实现合入后的成功路径；
- M06-A Integrated 回归 51/51、Skipped 0、`STAGE_VERIFIED`；M02 和完整 M07 在当前基线仍按设计非零 `PENDING_INTEGRATION`，M07 资产 16/16 通过；
- 未修改生产代码、POM、Web 或 package；未连接真实 Redis/MySQL/模型/浏览器/Embedding/Qdrant，M03-B 和最终 M07 仍待实现；
- 详细交接见 `docs/memory/handoffs/M07-B.md`。

### M03-A / M07-B 协调集成验证

- 合并 M02 与 M03-A 时发现两份 `ai.memory` YAML 节点会形成重复 key 并可能覆盖持久化或 Runtime 配置；已合并为单一 `ai.memory` 树，保留 M02 的 enabled/schema/legacy 与 M03-A 的 runtime-state；
- 协调复核将 Conversation/Trace 标识硬上限与持久化契约统一为 64 字符，并将旧 `ctx:*` 兼容 TTL 的可配置硬上限固定为 10 分钟；配置不能把兼容窗口放大到 30 分钟，新增断言已通过；
- 首次 M03-A Integrated 门禁因实现将模型合理拆为 `RuntimeStateSnapshot/Update/Scope`、而脚本只接受单一 `RuntimeState` 类产生误报；已修正门禁接受冻结语义下的拆分模型，同时保留 Service、Redis persistence、白名单、TTL、clear/consume 和专项测试检查；
- 修正后阶段门禁实际发现 46 项：36 项通过、10 项仅 M03-B/M04/M05 预期跳过、Failures 0、Errors 0；两个 M03-A 固定目标 skipped=0，报告为 `STAGE_GATE / M03_A / STAGE_VERIFIED`、`finalAcceptance=false`；
- `readinessMissing` 继续明确记录 `M03B_DELETION_CASCADE_PENDING` 与 `M03B_OWNER_APPROVAL_PENDING`；未连接真实 Redis，完整 M03 和最终 M07 尚未完成。
### M06-B 后端结构化 Conversation API（`feature/memory-m06b-backend-api`）

- 状态：`BACKEND_API_VERIFIED / FRONTEND_UI_INTEGRATION_PENDING`；起始基线 `6bfb979`；完整 M06 仍为 `PENDING`；
- 已实现 `/api/ai/conversations` 的创建、列表、详情、消息分页、归档、恢复和永久删除七个资源端点；响应 DTO 与 M06-A 的 Conversation/Page/Public Message/Message Page/Delete 契约对齐，时间字段固定输出 ISO-8601 字符串；
- 所有端点的所有者只来自 `RequestUserContext`，请求体或查询中的 `userId/admin` 不参与授权；不存在、已删除和异主访问继续统一为 `CONVERSATION_NOT_FOUND`，没有管理员绕过；
- 列表游标只承载冻结的 `updatedAt + conversationId` seek 边界，并使用带域隔离的 HMAC 签名和规范 Base64URL 编码；篡改、异密钥、格式错误或非规范编码统一返回 HTTP 400 + `INVALID_CONVERSATION_CURSOR`，不回退第一页；生产可用 `AI_MEMORY_CURSOR_SIGNING_KEY` 配置跨重启/副本共享密钥，未配置时回退 `OMNIDOC_JWT_SECRET`，两者都缺失时仅使用跨重启稳定的本地开发回退值；
- 公共消息采用服务端拒绝优先投影：仅保留 USER/ASSISTANT TEXT 和 `displayable=true` 的 TOOL EVENT；SYSTEM、TOOL_CALL、TOOL_RESULT、隐藏事件和内部 metadata 均不返回，工具事件正文从受控 label/toolName/status 重建而不透传原始工具结果或审批信息；
- 新结构化 API 使用真实非 200 HTTP 状态和 `{errorCode,message}` 错误载体，覆盖 400/401/404/409/503/500；未改变现有旧 chat API 的 `Result` 兼容格式；
- 后端 API 专项与旧 Controller 联合回归 25/25 通过；M02 Integrated 门禁的 Report Schema 5/5 通过，集成选择 71 项发现、59 项通过、12 项仅 M03-M05 按计划跳过、Failures 0、Errors 0，四个 M02 固定目标 skipped=0，阶段报告为 `STAGE_VERIFIED`；
- 未运行真实 MySQL/Redis、完整服务、真实 JWT 多用户、前后端联调或浏览器端到端；未修改 Agent 主链、Reflection、Context Manager、Runtime、Summary 或现有 UI。M06-C 仍需完成真实前端联调和 UI 接管，详细交接见 `docs/memory/handoffs/M06-B.md`。

### M03-B 与完整 M03 阶段门

- 已在永久删除 durable Conversation 之前统一清理 Runtime State、关联 Agent 任务快照和待审批引用；任何必需 Redis 清理失败都会显式返回并保留 MySQL Conversation/Message 以便幂等重试；
- continuation、approval confirm 与 approval cancel 均在服务层从认证上下文取得用户，并在消费快照、令牌或修改状态前同时验证 snapshot owner 与 Conversation owner；V1 不提供管理员绕过；
- 完整 M03 门新增四组正/负 fixture，能识别缺失删除级联以及“先改变状态、后校验所有者”的错误实现；阶段状态与最终 `AUTO_VERIFIED` 保持分离；
- M03 Integrated 门实际运行 69 项：61 项通过、8 项仅属于 M04/M05 的未来项，Failures 0、Errors 0；四个冻结 M03 目标全部执行且 skipped=0，报告为 `STAGE_GATE / M03 / STAGE_VERIFIED`；
- 该门仍使用本机确定性替身，未验证真实 Redis 故障、真实 MySQL、真实模型、完整 M07 或人工验收。

### M04 统一 MemoryContextBuilder 与 Agent 接入

- 已实现统一受预算上下文组装，覆盖 Planner、Follow-up Planner、Direct Answer、RAG Answer、Reflection、完成度评估和 Final Synthesis 七类 Conversation Agent 模型调用；
- 当前 USER 在模型调用前持久化，当前消息只注入一次；Summary coverage 后的近期原始消息按 token 预算保留，Tool Call/Result 以原子对裁剪；
- System/当前消息/必要工具对的优先级高于 Runtime、Summary 和旧历史；Summary 被裁剪时 coverage 不再生效，不引入不可见缺口；
- 超限输入保留 USER 并追加脱敏失败 EVENT，Agent API 返回 HTTP 413 + `MEMORY_CONTEXT_TOO_LARGE`；日志仅记录长度、计数和裁剪原因；
- 专项与 Agent/Controller 回归实际发现 60 项：56 项通过，4 项仅为 M05 预期挂起，Failures 0、Errors 0；M04 四个冻结目标 Skipped 0；
- 未连接真实 MySQL/Redis/模型/浏览器，M05、M06-C 和 M07 仍待完成；详细交接见 `docs/memory/handoffs/M04.md`。

### M05 Structured Rolling Summary

- 新增 `ai_conversation_summary` V002 幂等迁移和一对一当前版本存储；永久删除 Conversation 时由外键级联删除 Summary；
- 实现“上一版有效 Summary + coverage 后连续增量”的 token 阈值滚动更新，使用完整覆盖区间规范 SHA-256 digest、逐版本 CAS 和精确幂等判断；并发旧版本不能覆盖新版本；
- 实现严格 JSON 结构、枚举、来源、哈希、状态转换和旧条目保留校验；助手建议不能单独形成 confirmed decision，助手文本不能单独确认事实；
- 摘要输入先确定性脱敏，工具正文和 EVENT 正文不进入模型；单条来源超过摘要输入上限时不静默截断、不调用摘要模型、不推进 coverage；
- 摘要模型使用独立超时、有限工作线程和有界队列；生成、Schema、digest、来源或 CAS 失败均保留上一版摘要与原始消息，Agent 主回答不被摘要失败阻塞；
- 存储 JSON 与送入 Agent 的紧凑渲染文本分离；M04 的 `ConversationSummaryProvider` 已由生产 Summary Service 接管；M05 不调用 Embedding 或 Qdrant；
- M05、M04、Agent/Controller、存储和 Summary Schema 组合回归实际执行 79/79，Failures 0、Errors 0、Skipped 0；四个 M05 冻结目标全部执行；
- 当前确定性环境没有为摘要建立新的文档/工具权威来源解析器：工具正文仍被省略，新 DOCUMENT 引用被拒绝，因此不把未可验证的工具或文档结论升级为 confirmed fact。这是事实准确性优先的保守边界；
- 未连接真实 MySQL、Redis、模型或浏览器，完整 M07 与用户人工验收仍待完成；详细交接见 `docs/memory/handoffs/M05.md`。

### M06-C 前端完整会话体验

- 状态：`TARGETED_VERIFIED / REAL_BROWSER_AND_SERVICE_PENDING`；起始基线 `6ebdb2f`；
- Global Assistant 与 Editor 已统一使用结构化 Conversation 工作区，支持刷新恢复、活动/归档列表和继续分页、消息向前分页、真实新建、归档、恢复和二次确认永久删除；
- 两个入口不再使用 `temp_*`、`default_conv`、默认用户或本地伪助手回答；Agent 提交携带真实 Conversation ID 和安全 UUID `clientRequestId`，创建/执行失败保留草稿；
- 已补齐最后操作获胜的会话激活竞态、生命周期 busy 防护、并发加载计数、删除后的本地状态清理和无效 JWT 返回登录页；
- M01 缺陷基线已翻转为 M06 目标回归；前端契约 25/25、UI 目标回归 6/6、独立 M06-C 验收由起始基线 0/11 转为集成后 11/11、异步轮询 2/2、生产构建通过；
- 未运行真实浏览器、真实 HTTP、MySQL、Redis、模型或双用户 JWT；异步 job 提交后刷新恢复轮询仍待真实链路验证。详细交接见 `docs/memory/handoffs/M06-C.md`。

### M07 最终自动验收

- 两个独立 Codex 任务分别交付 M06-C 前端验收和 M07 严格门禁，主协调任务完成复核、适配、合入与最终验收；
- 统一入口 `scripts/memory/run-m07-acceptance.cmd -Integrated` 在干净工作区退出 0，报告状态 `AUTO_VERIFIED / MANUAL_PENDING`；
- 自动计数为资产 16 + 精确 Java 129 + 前端 42 = 187，Failures 0、Errors 0、Skipped 0；异步任务测试 2/2 和 Web 生产构建另外通过；
- message loss、重复 sequence、coverage gap/duplicate、stale overwrite、unsupported confirmed items、跨用户成功访问、token 超预算和全部删除残留均为 0，七条模型调用路径 7/7；
- MCP 搜索集成回归 80/80 通过并完成静态泄漏检查；Qdrant 回归与真实外部依赖按各自验收边界单独记录；
- 完整服务回归发现并修复 Summary JDBC Store 的 Spring 代理启动缺陷；随后 `AllModelsTest` 应用上下文 1/1 通过，完整本地 `ai-service` 291 项无失败/错误，3 项仅因真实 Qdrant 未启用而跳过；
- 报告明确记录真实浏览器/JWT、MySQL、Redis、模型、Qdrant 和远程 Embedding 未执行。详细交接见 `docs/memory/handoffs/M07-final.md`，人工清单见 `docs/memory/04-HUMAN_ACCEPTANCE.md`。

## 维护规则

每次任务完成后更新本文件，至少记录：

- 本次形成的真实进展；
- 实际执行的测试或检查及结果；
- 尚未验证的事项和剩余风险；
- 下一步动作。

若没有运行或测试，应明确写“未运行”或“未验证”，不得推测结果。

## 历史文档基线验证（2026-07-26）

- `git diff --cached --name-status`：暂存区仅包含本次要求新增的四个文档。
- `git diff --cached --check`：通过，未发现空白错误。
- 业务测试：未运行；本次仅新增文档，不修改业务代码，运行基线仍待下一阶段验证。

## 变更记录

| 日期 | 阶段 | 记录 |
| --- | --- | --- |
| 2026-08-11 | Memory M07 最终自动验收 | 独立门禁与前端验收合入主分支；统一入口 187/187、失败/错误/跳过均为 0，异步轮询 2/2、Web 构建通过，状态 `AUTO_VERIFIED / MANUAL_PENDING` |
| 2026-08-11 | Memory M06-C 前端会话体验 | Global/Editor 接管结构化会话历史、新建、分页、归档/恢复/删除和 `clientRequestId`，移除临时/默认 ID 与伪回复；前端契约 25/25、翻转回归 6/6、独立验收 0/11→11/11、构建通过，真实浏览器与服务联调待验收 |
| 2026-08-11 | Memory M05 Structured Summary | 实现 V002 Summary 存储、连续增量滚动摘要、规范 digest、严格来源/状态校验、CAS、独立超时和失败保留；相关组合回归 79/79，真实模型与最终验收仍待执行 |
| 2026-08-11 | Memory M04 统一上下文 | 实现按模型 token 预算的 MemoryContextBuilder，七类 Agent 模型路径统一接入，USER/工具/助手结构化追加，超限失败 EVENT 与 HTTP 413 稳定错误；专项回归 60 项无失败/错误，4 项仅 M05 挂起 |
| 2026-08-11 | Memory M06-B 后端结构化 API | 实现七个 Conversation 资源端点、认证所有者隔离、HMAC 稳定游标、公开消息白名单脱敏和真实 HTTP 错误载体；专项及旧 Controller 联合回归 25/25、M02 Integrated 阶段门禁通过；真实前端联调与 UI 接管仍待 M06-C |
| 2026-08-11 | Memory M02 持久化 | 在隔离分支实现 MySQL/Spring JDBC Conversation 与结构化 Message、事务 sequence、幂等、所有者隔离、生命周期和旧 Redis 按需导入；H2/MySQL-mode 专项与 Agent/Controller 回归通过，真实 MySQL 集成待协调复核 |
| 2026-08-11 | Memory M03-A Runtime State 核心 | 在独立分支实现五字段 typed Runtime State、conversation+trace 隔离、TTL/大小/消费生命周期、显式 Redis fail/degrade、清理接口与旧 ctx 白名单短期兼容；前两个 M03 门禁启用，删除与 Agent/owner 集成仍挂起 |
| 2026-08-11 | Memory M01 基线验证 | 三路 Codex 在隔离工作树完成后端 16 项行为基线、前端 7 项行为基线和 14 项验收资产；协调集成复跑通过，16 个 M02-M05 目标契约仍明确挂起，生产实现未开始 |
| 2026-08-11 | Memory V1 概念冻结 | 保留三级记忆长期架构，首个交付只实现 Conversation Memory；冻结目标、作用域、摘要和上下文契约，并拆分 M01-M07 实现任务，业务代码尚未修改 |
| 2026-08-10 | Qdrant 自动验收 | QV01-QV04 按顺序集成并由协调提交 `3457737` 补齐生产装配；专用入口 159/159、完整回归 178 个测试无失败/错误、四项硬门禁和资源清理通过，人工验收待执行 |
| 2026-08-10 | Qdrant QV01-QV04 | 从共同基线 `3ec7a0f` 创建四个独立分支、工作树和 Codex 任务并开始并行开发；专项实现和真实 Qdrant 尚未验收 |
| 2026-08-09 | Qdrant QV00 | 冻结双后端目标、Java 契约、配置、任务边界与验收规则；新增确定性契约和 HNSW 基线测试，并发现覆盖写入和删除残留旧 HNSW 命中缺陷，交由 QV01 修复 |
| 2026-08-09 | MCP 搜索自动验收 | T01-T04 已按冻结顺序合并，补齐发现到工具目录的集成连接器；集成模式 78/78、完整回归 99/99、静态泄漏检查通过，真实 Tavily 与人工验收待执行 |
| 2026-08-09 | MCP 搜索 T01-T04 | 从共同基线 `61266db` 创建 4 个独立分支、Git 工作树和 Codex 任务，分别开始连接层、工具目录、Agent 路由与独立验收开发 |
| 2026-08-09 | MCP 搜索 T00 | 方案 A 已执行：原有 34 个文件审查后形成 checkpoint `51caddf`；T00 契约、测试基线和任务拆分已完成，真实 MCP 尚未接入 |
| 2026-07-26 | 需求冻结 | 初始化项目上下文、奖学金审查 V1 需求、协作约定与进展文档；未进行运行基线验证 |

## 项目现状与后续路线梳理（2026-10-13）

- 本次按用户要求仅分析现状和制定计划，未开发功能。阅读基线为本地 `integration/core-baseline`（`ca3423a`），并只读对照 Review M0 后续生产候选及验证记录；未合并 main，未推送。
- 通用底座已包含文档工作台、Agent/MCP、RAG 双向量后端及迁移、Conversation Memory 和通用异步请求。MCP、Qdrant、Memory 是递进历史，不需要作为互相独立的功能重复合并。
- 新确认的集成边界：上传、解析、保存、索引是不同状态；当前编辑器主要手动索引，文档保存/删除/恢复与索引版本尚需统一。部分旧编辑器异常路径仍展示示例分析或分段，不能作为成功证据。
- 解析主要输出文本；文本层 PDF 与图片 OCR 路径不等于完整扫描 PDF OCR 或带页码坐标的证据模型。当前前端协作主要使用防抖保存和轮询同步，不能仅凭后端 WebSocket 代码宣称完整冲突安全协作。
- 明确展示口径：文档写入工具返回编辑器动作，仍需保存；PPT 为 HTML 演示文稿；AIOps 主要为指标、阈值告警和状态记录；会话刷新恢复历史不等于恢复正在执行的异步任务。
- Review M0 后续分支已实现规则确认、材料关联、安全归档处理、齐全性/命名检查、证据报告、报告过期，以及数据库任务/幂等/租约/检查点等能力；前端闭环和验收尚未完成。最新 B 预检查记录仍有日志项失败，旧 A 通过不能替代新候选验收；原环境未提交的 7 个修复文件不能视为已包含在当前 Git 历史。
- 建议顺序：固定可运行基线与真实状态 → 补文档/索引/引用一致性 → 收尾 Review M0 并接前端 → 持续质量评测 → 按目标岗位选择通用任务可靠性或 V1 文档智能深化。任务可靠性应优先复用经验证的 Review M0 能力，不重复从零建设。
- 完整分析报告交付于本次聊天工作区 `outputs/OmniDoc_项目现状与开发路线_2026-10-13.md`。用户已验证的简历指标继续按其原测量处理，本次没有重新测量。
- 本次检查：完成源码与项目文档阅读、Git 分支/提交/差异检查；`git diff --check` 通过，仓库变更范围仅 `docs/PROGRESS.md`，分析报告位于聊天工作区。业务测试、构建、真实数据库/模型/浏览器验收均未运行；不把源码存在或历史测试通过表述为本次运行验证。
