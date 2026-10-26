# 文档—索引—引用一致性：第二步

日期：2026-10-21。起始分支 `integration/core-baseline`，起始提交 `19c61c1`。用户已授权实施；本对话负责决策与验收，子Agent分别实施文档后端、AI索引/检索、前端。本文是目标和契约，不能作为已经通过的验收报告。

2026-10-26实施及本轮范围验收完成，证据与未验证边界见 [最终报告](RESULT-2026-10-26.md)。

## 用户决策与范围

- 文档保存或恢复后，旧索引立即停止参与新的问答。最新索引未完成/失败时明确告知，不回退旧内容。
- 历史回答引用保持指向回答当时的准确版本；打开时重新检查当前文档权限和删除状态，历史内容不能替换为最新正文。
- 继续使用无需外部模型的验证方式。成功索引/回答路径可使用明确标识的测试替身，但不能在生产配置中伪造Embedding，不能宣称真实模型效果已验证。
- 完成可靠索引任务、版本绑定、原子发布、删除失效、失败重试、来源定位和前端闭环；不合并Review M0，不建设新的通用任务平台，不扩展页码/坐标识别或专业审查功能。
- 保留现有VectorStore双后端、RAG/Agent和Conversation能力；没有用户授权不更新main或推送。

## 不变量

1. **保存事实唯一。** 数据库内的当前正文、明确版本的不可变快照、索引任务在同一事务落地。原上传文件不变；MinIO编辑内容对象可以作为兼容派生物，不能反过来覆盖已提交事实。
2. **版本不倒退。** 保存及历史恢复产生新版本；使用expectedVersion和数据库并发控制阻止丢失更新。重复历史编号不能猜选；legacy记录保留，不通过删除原历史强行获得唯一性。
3. **不索引草稿。** 索引请求只指定文档、期望版本和服务端支持的配置，正文与归属从可信快照获取。浏览器正文、userId、hash均不能充当索引事实。
4. **发布前不可见。** 每次尝试使用隔离generation；所有段和向量完成后，再核对版本、配置、文档状态和租约令牌发布。旧worker迟到不能覆盖新版本或复活删除文档。
5. **旧版立即失效。** 保存、恢复、删除事务清除有效指针；向量/关键词/混合/重排及Agent检索统一检查当前版本和有效generation。读权限或状态核验不可用时显式失败，不默认放行。
6. **中断可恢复。** 数据库任务具备幂等键、可到期租约、有限尝试和明确失败状态，允许重试。后台不保存用户Bearer令牌。内部接口使用独立服务认证，无弱默认密钥。
7. **清理可重试。** 删除先阻止检索与引用读取，再异步清理存储；失败或旧尝试残留不能参与检索，不可宽泛删除别的用户/版本数据。
8. **引用有依据。** 只有真实检索结果可以生成来源引用。引用绑定documentId、版本、canonical内容hash、片段位置与hash；服务端从不可变快照验证切片，模型文本和客户端字段不能创造“已验证来源”。
9. **历史可复查。** 引用随助手消息持久化并通过公开白名单返回；刷新、分页、重新打开会话后保留。旧消息没有来源就保持没有。删除或失去访问权后引用不再返回原文。
10. **状态真实。** 解析、保存、索引、草稿分别显示；summary存在不代表AI就绪。未就绪时不伪装成成功空结果，不以生成模型的错误提示作为成功回答。

## 文档快照

- 复用 `document.content`、`document_version`；可信新快照有唯一revision标记，旧重复版本保留为legacy。
- 保存明确 `contentFormat=PLAIN_TEXT|HTML`，保留原始正文和规范化文本；HTML使用已有解析能力保留块/段落边界，去除script/style，不能把所有换行压平。
- 固定normalization/parser版本：可信快照记录normalization_version与parser_version；严格上传/重新解析标记strict-parser-v1，显式正文保存标记explicit-content-v1，恢复继承源快照标记，legacy保持NULL不倒推。内容hash基于canonical UTF-8；引用offset统一Java/JavaScript UTF-16，并验证代理对边界。新正文默认最大2MiB UTF-8，可配置且超限明确413。
- 无法可靠判断格式或来源的旧当前文档显示MIGRATION_REQUIRED，经显式保存形成可信新版本后再索引；歧义历史版本不得静默选第一条。
- 解析失败保留原文件和真实失败状态，不能把吞异常后的空正文排入成功索引。合法空正文与解析失败分开处理。

## 公共API契约

成功使用现有 `Result.data`；错误使用真实HTTP状态和 `errorCode/message`（允许仍带Result.code以兼容）。公开身份仅来自已验证JWT。

| 端点 | 输入/输出 |
| --- | --- |
| GET `/api/documents/{id}/index-state` | `{documentId,currentVersion,indexedVersion,indexState,queryable,activeGenerationId,taskId,errorCode,retryable}` |
| POST `/api/documents/{id}/index` | `{expectedVersion,strategy}`，202返回状态；不接收正文作为事实 |
| POST `/api/documents/{id}/index/retry` | `{expectedVersion}`，202返回状态 |
| 保存/恢复 | 提供expectedVersion；服务端返回实际VO.version。恢复产生新版本，不覆盖历史 |
| GET `/api/documents/{id}/references/{referenceId}` | 返回原版本的精确内容切片和CURRENT/HISTORICAL状态；当前JWT/ACL/未删除检查优先 |

indexState为 `NOT_INDEXED / QUEUED / BUILDING / READY / FAILED / DISABLED / MIGRATION_REQUIRED / DELETED`。文档与前端已对齐：解析失败返回 `FAILED + errorCode=PARSE_FAILED + retryable=false`；合法空正文返回 `DISABLED + errorCode=EMPTY_CONTENT`。解析重试使用独立 `POST /api/documents/{id}/parse/retry {expectedVersion}`。只有当前版本/配置已发布时queryable=true。

Conversation公共消息、即时Agent及历史结果共同使用顶层 `references` 数组，旧消息默认空数组。引用字段：`referenceId,documentId,documentVersion,documentTitle,segmentId,generationId,contentHash,startOffset,endOffset,segmentHash`。不在引用列表携带完整正文。

resolve返回：`referenceId,documentId,documentVersion,currentVersion,documentTitle,status,content,startOffset,endOffset,segmentHash`。无权限/已删除/不存在统一404，锚点无法核验409，依赖不可用503；不能将当前正文当作历史引用回传。

内部任务协议由AI与文档实现者对齐：AI拉取文档服务的任务并续租、完成或报告失败；文档服务是发布有效generation的事务权威。不得由AI进程直接任意改写文档数据库或将长期用户凭据保存到任务。

## 前端范围

- Editor保存回填服务端版本，保存途中继续编辑仍显示草稿；冲突保留本地内容。
- 手动更新索引使用已保存版本，未保存时明确“保存并更新索引”；保存失败不得继续。
- 保存、恢复、远程协作版本变化后立即刷新/失效本地索引状态，防止迟到轮询把旧版标为READY。
- 协作保存复用统一API并检查结果、通知版本变化，不在本轮重做完整协作算法。
- Dashboard按真实索引状态展示，移除以摘要存在判断“AI已就绪”的逻辑。
- 共享只读引用面板覆盖Editor、chat-mode、全局助理和Agent Workbench；引用正文按纯文本显示，清楚标明历史版本，不能捏造页码或坐标。
- 保持既有“全局RAG”语义，不擅自限制为当前编辑文档。

## 验收门

必须有实现对应的确定性测试，并尽可能以隔离真实MySQL/Redis/HTTP和浏览器验证：

1. 新建/保存形成准确快照与任务；事务回滚无半成事实；并发旧expectedVersion得到409。
2. 草稿不进入索引；未配置/禁用Embedding明确失败，不尝试外部请求。
3. V1就绪后保存V2，V1立即停止检索；V2完成后仅V2参与检索。
4. V2构建途中保存V3，V2迟到不得发布；worker中断后过期租约可恢复，旧租约不得提交。
5. 重复任务幂等；部分写入、发布失败和重试不混用generation、不重复可见片段。
6. 删除后立即无新召回且引用不可读，清理故障可重试；恢复旧内容作为新版本重新索引。
7. 引用内容与版本/hash/位置一致，中文、emoji、HTML段落不偏移；篡改引用、越权和legacy歧义被拒绝。
8. 历史回答刷新/分页引用仍在，点击读历史片段并标HISTORICAL，不偷换当前文本。
9. 前端真实页面断言限定在引用面板内部，避免命中编辑正文造成伪通过。
10. 原有权限、会话、向量双后端与403修复边界不回退；测试替身结果与真实模型验收分别记录。

## 文件与执行分工

- backend_regression：AI代码、索引worker、检索及引用传播、AI测试；唯一Maven执行者，统一编译/运行两个后端模块。
- runtime_baseline：document-service、版本/任务数据库与迁移、文档测试；完成后独立负责受控真实运行验证和环境收尾。
- frontend_baseline：web代码、交互和测试、最后真实浏览器验收；保留startup测试独立进程。
- 主协调：本契约、PROGRESS/context、跨模块审阅与验收报告、最终本地checkpoint。各Agent不自行提交或推送。

当前状态：设计已冻结核心语义，实施与测试尚未验收；细节调整须先在协作方同步并记录，不把本文目标写成已有能力。
