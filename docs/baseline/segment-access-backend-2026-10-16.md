# 分段读取权限与真实状态：后端交接（2026-10-16）

## 范围与结果

起始分支 `integration/core-baseline`，HEAD `05c2d63`。仅修复分段 GET 的鉴权和状态区分，不修改索引/删除写流程、不自动索引、不接外部模型、不合入 Review M0。本轮新增 29 项后端测试（AI 22、文档权限 7）全部通过；两模块本地回归、打包通过。真实服务与浏览器结果由运行/前端执行者另行记录。

原问题：`RagController` 把 Redis `doc:{id}.userId` 当作文档权限依据，未索引文档没有该记录时被拒绝；旧元数据、段 ID 和正文读取辅助方法还会吞异常，无法区分缺记录和故障。新分段链不再调用这些宽松 helper，其他 RAG 接口保持原逻辑。

## API 契约

`GET /api/ai/rag/document/{documentId}/segments`：

| 状况 | HTTP / Result.code | data |
| --- | --- | --- |
| JWT 文档读权通过，且 doc、doc_segments、content 索引记录全部缺失 | 200 | `{indexState:"NOT_INDEXED",segments:[]}` |
| JWT 文档读权通过、索引归属为当前用户、片段列表与正文完整且读取期间未改写 | 200 | `{indexState:"SEGMENTS_AVAILABLE",segments:[{segmentId,content}]}` |
| 未登录或无效 JWT | 401 | null |
| 有文档读权，但已有索引归属其他用户 | 403 | null |
| 文档不存在、非 active 或无读权 | 404 | null |
| 索引记录残缺、类型不合法、无可验证段列表、跨文档段 ID、重复 ID 或读取期间更新 | 409 | null |
| 文档权限服务、数据库或 Redis 读取不可用，或权限响应格式/用户/文档不匹配 | 503 | null |

错误仍为 `Result {code,message,data:null,timestamp}`；使用真实 HTTP 状态，不再通过 HTTP200 冒充成功。没有新增 errorCode 字段。403/404 不返回索引归属、片段正文或文档正文。

`SEGMENTS_AVAILABLE` **仅证明保存的片段可读**，不证明向量化、召回或完整索引成功。现有索引实现存在“分段为空，直接保存完整文档向量”的合法路径；该路径没有可验证分段列表时返回409，不能据此断言向量索引损坏。NOT_INDEXED也只是当前没有索引存储记录，不代表从未尝试过索引。

## 权限依据与安全限制

- 原文档详情虽调用 ACL，但其 RequestUserContext 可回退信任 `X-User-Id`，且对应路由 permitAll。不能仅凭详情可读取就把它作为可信权限源。
- 新增 `GET /api/documents/{id}/read-access` 窄接口，只接受经过真实 Spring Security JWT 过滤器建立的 principal；不调用旧 header 回退上下文，不使用查询 userId。按 MySQL active 文档及原 owner/editor/viewer ACL判断。
- 窄接口成功仅返回 `{documentId,grantedUserId}`；不存在/无权统一404，避免文档存在性枚举；数据库故障503。无 JWT 即使伪造 X-User-Id也401。
- AI 权限客户端仅转发原 Authorization，不转发 X-User-Id；严格检查成功码与回传文档/当前用户相符。连接超时2秒，读取超时3秒；上游错误正文不向外透传。
- 文档 ACL 通过后仍保留当前用户索引归属限制，不开放其他用户私有索引。没有沿用旧 helper 的 admin 归属豁免。
- Redis 读取用专用 SessionCallback，在同一连接 WATCH 文档、段列表、原文索引键和段正文键，通过空 MULTI/EXEC 确认读取期间未变化；中止409，依赖异常503。没有写入索引数据。
- 段 ID 接受当前三个策略的 `documentId_seg_N`、`documentId_chapter_N`、`documentId_semantic_N`，拒绝跨文档引用；列表上限10000并拒绝重复/坏类型/缺正文。没有把缺正文替换为空文本。

兼容边界：只有独立 RAG 索引、没有真实 active 文档实体的旧记录会404；协作者文档读权不自动赋予另一用户的索引读权；管理员没有额外索引归属豁免。这是本分段读取端点的权限收紧，不代表所有 RAG 接口或旧文档 header 回退问题都已修复。

## 修改路径

- AI：`config/SecurityConfig.java` 仅为该 GET 的未认证响应指定401；`controller/RagController.java` 移除旧分段方法；新增 `controller/DocumentSegmentsController.java`、`client/DocumentReadAccessClient.java`、`rag/DocumentSegmentReadService.java`、`rag/SegmentReadException.java`。
- 文档：新增 `controller/DocumentReadAccessController.java`；`service/DocumentAccessService.java` 暴露只读 canRead，复用原 ACL 判断。
- 测试：`DocumentSegmentReadServiceTest` 10项、`DocumentSegmentsControllerTest` 5项、`DocumentReadAccessClientTest` 7项、`DocumentReadAccessControllerTest` 7项。HTTP测试使用真实 Spring MVC/Security 配置和真实签名测试 JWT；数据库、Redis和权限 HTTP 上游为确定性替身。
- POM、公共 JWT 逻辑、索引写入、删除、其他 RAG 读取、文档旧权限接口均未修改。

## 实际验证与失败记录

工具沿用便携 JDK17、Maven Wrapper3.9.12。命令在仓库根目录运行，输出保存在 `.local/baseline-evidence/segment-access-2026-10-16/`。

1. 首轮专项命令选择四个新增类及 DocumentServiceImplTest，AI 20项中19通过、1个测试布置错误：循环对已 thenThrow 的 mock再次使用when触发旧异常。改成doThrow；保留 `backend-tests.log`，不视为通过。
2. 补齐Redis元数据、列表、正文三个独立故障测试后，AI专项22/22。随后package因运行中AI JAR被Windows锁定，repackage失败；document尚未运行。保留 `backend-tests-final.log`，不把该命令整体写为成功。运行执行者核对进程后停止AI/document解除锁。
3. 执行 `./mvnw.cmd -B -s .mvn/settings-local.xml -pl ai-service,document-service -am '-Dtest=*,!AllModelsTest,!DocumentServiceApplicationTests' '-Dsurefire.failIfNoSpecifiedTests=false' test`，退出0，`BUILD SUCCESS`。AI发现313、执行通过310、跳过3（真实Qdrant专用项，本轮未重跑）；document11/11（权限7+原文档单测4），失败/错误均0。common只有空JwtUtilsTest，未计测试通过。证据 `backend-full.log`、`backend-counts.json`、两个 `*-surefire/`。
4. 执行 `./mvnw.cmd -B -s .mvn/settings-local.xml -pl ai-service,document-service -am -DskipTests package`，退出0、全部4个Reactor模块SUCCESS。生成新的AI与document可启动JAR，已通知运行执行者启动。此打包步骤明确跳过测试，测试证据来自上一步。记录 `backend-package.log`。
5. `git diff --check` 通过；前端文件有既有换行转换提示，不是空白检查失败。

未访问外部模型/Embedding/Tavily；AllModelsTest明确排除，未把占位凭据调用或错误提示算作AI回答。正式M07要求干净候选，后续由主协调决定固定提交后再执行；此报告不预先宣布该门或真实环境通过。
