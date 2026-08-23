# Review M0 人工验收清单

## 状态规则

- 开始条件：最近一次 Integrated 机器报告为 `AUTO_VERIFIED / MANUAL_PENDING`。
- 自动门、资产门或本文档作者不得填写 `HUMAN_ACCEPTED`。
- 仅当操作者实际完成全部适用项、记录证据位置且没有未处置失败时，用户才能把本轮结论记为 `HUMAN_ACCEPTED`。
- 记录中只写 ID、摘要、数量、时间、状态码和脱敏证据位置；禁止粘贴材料正文、OCR 正文、对象键、预签名 URL、连接串、令牌或密钥。

环境记录：

| 字段 | 实际值 |
| --- | --- |
| 日期/时区 |  |
| 操作者 |  |
| 部署提交完整 SHA |  |
| MySQL 8 版本/实例标识 |  |
| file-service/MinIO 版本或部署标识 |  |
| document-service/OCR 版本或部署标识 |  |
| ai-service 实例数 |  |
| 机器报告位置与摘要 |  |
| 初始人工状态 | `MANUAL_PENDING` |

每项统一记录：`开始/结束时间、环境、操作者、合成输入类别、预期、实际、HTTP/Job/报告 ID、脱敏证据位置、PASS/FAIL/NOT_RUN`。

## A. 真实基础设施与所有者链

- [ ] A01：在真实 MySQL 8 启动 Review M0 隔离迁移，重启后再次校验；确认表、约束和历史事实保留。分别记录一条成功事务和一条可恢复数据库失败，失败不得生成业务 finding。
- [ ] A02：使用两个真实认证用户，通过 HTTP 创建案例；对案例、依据、材料、预览、任务、报告和下载逐类做异主访问，全部为冻结 404，且无成功状态、字节、长度或资源存在性侧信道。
- [ ] A03：通过生产 owner-aware file-service 访问真实 MinIO 对象，验证元数据、流式普通文件读取、摘要绑定和 ZIP 单条目读取。未授权用户不得通过 `fileId`、bucket、Header 或内部路径绕过。
- [ ] A04：分别制造 file-service 网络不可用与已知源对象不可用。前者为 Job/API 系统失败，后者为 `CANNOT_CONFIRM/SOURCE_FILE_UNAVAILABLE`；两者都不得变成 `MISSING`。

## B. 正式格式、ZIP 与文档失败

- [ ] B01：逐一上传 PDF、DOCX、XLSX、JPG、PNG、ZIP 六类顶层正式格式；五类普通文件形成材料，ZIP 只形成容器。
- [ ] B02：一个 ZIP 内放入 PDF、DOCX、XLSX、JPG、PNG 五类叶子，确认形成五个独立 `materialId`；目录项忽略，容器本身不满足清单且不执行清单文件名规则。
- [ ] B03：逐一验证嵌套 ZIP、非白名单叶子、`../`、绝对路径、驱动器/UNC、NUL/控制字符、重复 NFC 路径、加密条目、损坏/CRC 不符、条目数/单项展开量/总展开量/压缩比/深度/处理时限。全部拒绝优先，危险 ZIP 不发布部分材料。
- [ ] B04：对顶层扩展不受支持验证 HTTP 415；对白名单扩展但签名不匹配、加密或损坏验证冻结 `CANNOT_CONFIRM` 原因码。
- [ ] B05：通过真实 document-service/OCR 对 PDF、DOCX、XLSX、JPG、PNG 各跑成功路径；再跑解析失败、OCR 置信不足和服务网络失败。输入问题与系统失败必须分离，OCR 仅用于官方依据候选提取。
- [ ] B06：在当前提交材料集合存在任一未决 `CANNOT_CONFIRM` 时完成上传确认并审查，确认整份报告 `MISSING=0`，但可可靠执行的命名检查仍运行。

## C. 清单、上传确认、报告和证据

- [ ] C01：手动分类 `APPLICATION_NOTICE`、`FILLING_GUIDE`、`OFFICIAL_TEMPLATE`；核对官方提取、用户新增、用户改写和停用项目的来源谱系及定位证据。
- [ ] C02：分别尝试用候选清单、未逐项确认清单、含 `NEEDS_USER_DECISION` 的清单和依据变化后的旧清单提交审查；均应拒绝且不生成正式报告。
- [ ] C03：未确认上传完毕时提交正式审查，确认 HTTP 409 `REVIEW_M0_UPLOAD_NOT_CONFIRMED`、无 Job/报告且 `MISSING=0`。确认当前上传修订后再验证真实缺失项只产生一个证据闭合的 `MISSING`。
- [ ] C04：检查每个 `MISSING` 与 `FILE_NAME_NONCOMPLIANT` 同时具有规则证据和材料/材料集合证据；系统失败不得进入 findings。
- [ ] C05：对发现执行 `IGNORE`，确认发现和证据仍可读、ignored 计数增加、报告不可变且 assessment 仍为 `M0_NOT_READY`。
- [ ] C06：分别改变官方依据、正式清单、材料集、分配、逻辑名和引擎版本；每次事务提交后旧报告立即且不可逆地 `OUTDATED`，新审查创建新报告。
- [ ] C07：对同一最终输入执行单文件增量与完整重审，比较规范化发现、证据、计数和 assessment；制造未声明输入变化时必须得到 `REVIEW_M0_INCREMENTAL_BASE_MISMATCH`。
- [ ] C08：输入含错别字、漏填、资格、签章/日期、跨材料差异、证明支持和写作质量的合成材料，确认 M0 不产生这些范围外发现。

## D. 幂等、重启、并发与取消

- [ ] D01：对每类写端点重放同一 `Idempotency-Key` 和相同载荷，确认返回首次状态和首次逐字段响应；先改变资源、再重启 ai-service 后重放，响应仍与首次字节等价。相同 key 不同载荷为 409。
- [ ] D02：在 `QUEUED`、处理中 checkpoint、结果发布前和发布后分别重启 worker；确认从最后已提交 checkpoint 恢复，已完成单元不重复，最多一个正式结果。
- [ ] D03：两个 worker 并发竞争同一 Job，只有一个租约持有者推进；相同 `(caseId,inputFingerprint)` 最多一个报告。
- [ ] D04：分别取消 `QUEUED` 和 `RUNNING` Job，并在 `CANCEL_REQUESTED` 后立即重启；两者必须自动收敛为 `CANCELLED`，不开始新材料单元/远程调用且无正式结果。终态取消幂等返回原 Job。

## E. 下载、原件不变与敏感信息

- [ ] E01：修改普通文件逻辑名并下载，核对当前 Unicode 文件名、ASCII fallback、RFC 5987 `filename*` 和原字节摘要；CR/LF、路径分隔、点目录及控制字符不能注入响应头或服务端路径。
- [ ] E02：下载 ZIP 内一个叶子，只返回该叶子字节而不是整个 ZIP 或其他条目。
- [ ] E03：在附加、逻辑改名、审查和两类下载前后比较原始字节摘要、原始文件名、文件服务元数据、存储版本与 MinIO 对象；全部不变。
- [ ] E04：检查 ai-service、file-service、document-service 日志、异常、Job 错误、报告、evidence 和人工记录；不得含材料/OCR/预览/远程请求响应/Prompt 正文、对象键、bucket 内部路径、预签名 URL、连接串、凭据、令牌或堆栈。

## F. 待确认产品项

- [ ] F01：P-08 保持 `LOCAL_ONLY`。确认本地案例不构造或调用远程客户端。本轮远程供应商/模型未获产品确认，记录 `NOT_RUN / PENDING`；不得用测试 client 声称远程已验收。
- [ ] F02：P-09 页面入口仍未确认，记录 `NOT_RUN / PENDING`，不假设页面、路由、导航或通知存在。仅当后续有独立产品确认和实现提交时，才追加真实浏览器刷新恢复与错误展示验收。

## 最终签署

| 字段 | 实际值 |
| --- | --- |
| 适用项 PASS 数 |  |
| FAIL 数 |  |
| NOT_RUN/PENDING 项及原因 |  |
| 剩余风险 |  |
| 用户最终决定 | `MANUAL_PENDING` 或由用户填写 `HUMAN_ACCEPTED` |
| 用户/日期 |  |
