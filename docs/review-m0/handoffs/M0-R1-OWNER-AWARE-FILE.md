# Review M0 R1-C — owner-aware file-service 安全读取链交接

## 1. 基线、分支与提交

- 固定起始基线：`eb400ee3c9c5ee06afc82e600a5e673a4c4d88a9`
- 分支：`feature/review-m0-r1-c-owner-file-01a02df1`
- 独立 worktree：`C:\Users\lab\Desktop\docAI-main\.codex-worktrees\review-m0-r1-c-owner-file-01a02df1`
- 交接提交：本文件与实现位于同一个可独立 cherry-pick 的提交；提交完成后以该 worktree 的 `git rev-parse HEAD` 为完整 SHA。Git 提交内容无法自引用其最终 SHA，完整值同时记录在最终交付消息中。
- 契约冲突：未发现需要修改冻结枚举、HTTP JSON、Review 表、状态机或错误码的冲突。

本任务只实现 file-service 的可信 owner 写入/元数据/字节读取链，以及 ai-service Review M0 file/config 的生产客户端和最小装配。未修改 `idempotency/**`、`job/**`、review/report/evidence 引擎、acceptance、M0-07、文档/OCR/远程模型、公共 YAML/POM、Web、Agent、Memory/MCP/Qdrant 或 `docs/PROGRESS.md`。

## 2. 实现结论

### 2.1 file-service

- 新增 JWT 强制保护的 `POST /api/files/owned`、`GET /api/files/owned/{fileId}/metadata`、`GET /api/files/owned/{fileId}/content`。
- owner 只来自 `JwtAuthenticationFilter` 建立的 Spring Security principal；请求体、query、任意 `X-User-Id` 和 bucket 均不参与 owner 绑定或授权。
- 新上传以服务端 UUID objectKey 保存，元数据在同一逻辑写入中绑定 `owner_id`、原始文件名、声明 media type、精确 size、SHA-256、storage version 和有界签名分类；元数据写入失败时对对象做补偿删除，遗留孤儿也没有 owner 元数据因而不可读取。
- owner-aware 查询固定使用 `owner_id + file_id + ACTIVE`；owner-null 历史记录、跨 owner 和不存在均进入相同 404。
- 元数据响应不含 owner、bucket、objectKey、filePath 或对象存储凭据；内容响应带精确长度、SHA-256 和签名分类，字节流在 EOF 再次校验 size/hash。
- owner-aware 对象存储只在 owner 元数据校验完成后使用内部 bucket/objectKey；新写入固定使用配置 bucket，不使用现有 `FileStorageConfig#getBucketName()` 的 header/user bucket 推断。
- 旧 permitAll 文件 API 保持历史 owner-null 行为；旧元数据/list/search 明确排除新 `owner_id != NULL` 记录，防止旧接口泄漏新 owned 对象定位。旧接口对历史记录的既有不安全性没有在本任务中重构。

### 2.2 迁移

- 新数据库的 `file.sql` 已加入 `owner_id`、`sha256`、`storage_version`、`content_signature`、owner 查询索引和 owned-row 完整性 CHECK。
- 既有数据库使用 `db/file-service/V002__owner_aware_file_access.sql`。
- 迁移刻意不执行任何 owner 回填；历史 `owner_id=NULL` 合法保留但 owner-aware API fail closed。若业务需要使用历史文件，必须另行人工确认来源并做受控迁移，禁止从 bucket、objectKey、上传 header 或消息事件猜测 owner。

### 2.3 ai-service Review M0

- 新增生产 `HttpOwnedFileByteSource`，调用 file-service 的 owner-aware metadata/content 端点；`ReviewM0Configuration` 不再为文件访问注册 `VerifiedReviewM0FileAccess.failClosed(...)` 占位。
- 客户端只转发当前 HTTP 请求的 `Authorization: Bearer ...`，并要求 Spring Security principal 与 Review 传入 owner 完全一致。无当前可信 Bearer、身份不一致、下游 401/403、网络失败或不可信元数据/摘要均 fail closed，不生成合成字节。
- 下游 404 映射冻结 `NOT_FOUND/REVIEW_M0_RESOURCE_NOT_FOUND`；网络、身份链、协议或摘要不可信映射 `SERVICE_UNAVAILABLE/INTEGRITY_MISMATCH -> REVIEW_M0_FILE_SERVICE_UNAVAILABLE`（503）。
- `VerifiedReviewM0FileAccess` 保留下载前/后不可变元数据校验和客户端 SHA-256/size 校验；普通文件和 ZIP 单叶子继续由既有 M0-04 逻辑流式读取。
- `submissionFileName` 没有进入 file-service；逻辑改名仍只写 Review 表并只用于 Review 下载 `Content-Disposition`。

## 3. 修改文件

### ai-service 生产与测试

- `ai-service/src/main/java/com/javaee/aiservice/review/m0/config/ReviewM0Configuration.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/config/ReviewM0RuntimeProperties.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/file/FileServiceRequestAuthentication.java`
- `ai-service/src/main/java/com/javaee/aiservice/review/m0/file/HttpOwnedFileByteSource.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/config/ReviewM0ConfigurationTest.java`
- `ai-service/src/test/java/com/javaee/aiservice/review/m0/file/HttpOwnedFileByteSourceTest.java`

### file-service 生产、迁移与测试

- `file-service/src/main/java/com/javaee/fileservice/config/SecurityConfig.java`
- `file-service/src/main/java/com/javaee/fileservice/controller/OwnerAwareFileController.java`
- `file-service/src/main/java/com/javaee/fileservice/controller/OwnerAwareFileExceptionHandler.java`
- `file-service/src/main/java/com/javaee/fileservice/dto/OwnedFileMetadataDTO.java`
- `file-service/src/main/java/com/javaee/fileservice/entity/FileMetadata.java`
- `file-service/src/main/java/com/javaee/fileservice/exception/OwnedFileNotFoundException.java`
- `file-service/src/main/java/com/javaee/fileservice/exception/OwnedFileUnavailableException.java`
- `file-service/src/main/java/com/javaee/fileservice/mapper/FileMetadataMapper.java`
- `file-service/src/main/java/com/javaee/fileservice/security/AuthenticatedFileOwner.java`
- `file-service/src/main/java/com/javaee/fileservice/service/OwnedFileObjectStore.java`
- `file-service/src/main/java/com/javaee/fileservice/service/OwnerAwareFileService.java`
- `file-service/src/main/java/com/javaee/fileservice/service/impl/ConfiguredOwnedFileObjectStore.java`
- `file-service/src/main/java/com/javaee/fileservice/service/impl/FileMetadataServiceImpl.java`
- `file-service/src/main/java/com/javaee/fileservice/service/impl/OwnerAwareFileServiceImpl.java`
- `file-service/src/main/resources/db/file-service/V002__owner_aware_file_access.sql`
- `file-service/src/main/resources/file.sql`
- `file-service/src/main/resources/mapper/FileMetadataMapper.xml`
- `file-service/src/test/java/com/javaee/file/FileApplicationTests.java`
- `file-service/src/test/java/com/javaee/fileservice/controller/OwnerAwareFileSecurityTest.java`
- `file-service/src/test/java/com/javaee/fileservice/service/OwnerAwareFileServiceTest.java`

### 交接

- `docs/review-m0/handoffs/M0-R1-OWNER-AWARE-FILE.md`

## 4. 最低验收逐项状态

| 验收项 | 状态 | 证据 |
| --- | --- | --- |
| 数据迁移、索引、owned-row 完整性约束；历史 owner-null 无回填 | `PASSED` | `OwnerAwareFileServiceTest#migrationKeepsHistoricalOwnerNullableAndConstrainsOwnedRows` |
| 新上传绑定可信 JWT owner | `PASSED` | service 单元 + MockMvc JWT 正向；伪造 `X-User-Id` 不改变 owner |
| owner-null fail closed | `PASSED` | owner-aware Mapper owner predicate + service 404 测试 |
| 同 owner 元数据/字节读取 | `PASSED` | file-service 单元与 ai-service synthetic HTTP 组合测试 |
| 跨 owner与不存在统一 404 | `PASSED` | service + controller + Review adapter 测试 |
| 仅伪造 `X-User-Id` 无权 | `PASSED` | 无 Bearer 的 metadata/upload 均 401；adapter 不发请求 |
| 原始名/size/SHA-256/media type/签名/流一致 | `PASSED` | 上传快照、HTTP metadata/content、EOF 双层摘要校验 |
| 文件服务不可用/身份或摘要不可信 fail closed | `PASSED` | `HttpOwnedFileByteSourceTest`；无合成字节 |
| 逻辑改名前后原对象不变 | `PASSED` | M0-04 `material/file/archive` 精确回归 81/81 |
| 六类顶层格式与普通/ZIP 单副本下载 | `PASSED` | M0-04 精确回归 81/81 |
| M0-06 API/config | `PASSED` | 17/17 |
| file-service 既有精确回归与新增安全测试 | `PASSED` | 11/11；既有 `FileApplicationTests` 从无法定位应用的空上下文改为不冒充外部启动的入口注解烟测 |
| ai-service + file-service compile | `PASSED` | Reactor `BUILD SUCCESS` |
| 默认 Review asset gate | `PASSED / ASSET_ONLY` | 26/26，exit 0，`ASSET_VERIFIED`；不代表生产外部链验收 |
| `git diff --check` | `PASSED` | 最终交接写入后复跑 |

## 5. 实际成功命令与计数

计数顺序均为 discovered / passed / failures / errors / skipped。

1. file-service 精确回归：

   ```powershell
   .\mvnw.cmd -s .mvn\settings-local.xml -pl file-service -am '-Dtest=FileApplicationTests,OwnerAwareFileServiceTest,OwnerAwareFileSecurityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
   ```

   结果：`11 / 11 / 0 / 0 / 0`，`BUILD SUCCESS`。

2. Review 文件 adapter/config：

   ```powershell
   .\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am '-Dtest=HttpOwnedFileByteSourceTest,VerifiedReviewM0FileAccessTest,ReviewM0ConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=1' '-DargLine=-Xms96m -Xmx384m' test
   ```

   结果：`14 / 14 / 0 / 0 / 0`，`BUILD SUCCESS`。

3. M0-04 精确回归：

   ```powershell
   .\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am '-Dtest=com.javaee.aiservice.review.m0.archive.*Test,com.javaee.aiservice.review.m0.file.*Test,com.javaee.aiservice.review.m0.material.*Test' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=1' '-DargLine=-Xms96m -Xmx384m' test
   ```

   结果：`81 / 81 / 0 / 0 / 0`，`BUILD SUCCESS`。

4. M0-06 精确回归：

   ```powershell
   .\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am '-Dtest=com.javaee.aiservice.review.m0.api.*Test,com.javaee.aiservice.review.m0.config.*Test' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=1' '-DargLine=-Xms96m -Xmx384m' test
   ```

   结果：`17 / 17 / 0 / 0 / 0`，`BUILD SUCCESS`。

5. 双模块编译：

   ```powershell
   .\mvnw.cmd -s .mvn\settings-local.xml -pl file-service,ai-service -am -DskipTests compile
   ```

   结果：`BUILD SUCCESS`。

6. 默认验收资产门：

   ```powershell
   scripts\review-m0\run-m0-acceptance.cmd
   ```

   结果：exit `0`；`26 / 26 / 0 / 0 / 0`；状态 `ASSET_VERIFIED`；敏感信息扫描 1163 个文本文件无命中。

7. 差异检查：

   ```powershell
   git diff --check
   ```

   结果：exit `0`；无 whitespace error。

## 6. 已修复的失败尝试

- 首次 file-service 定向命令：PowerShell 未引用第二个 `-D`，Maven 参数解析阶段退出 1；`discovered=0`，属于命令错误。
- 第二次 file-service 定向：发现 9 项，4 passed / 0 failures / 5 errors / 0 skipped；Web slice 被应用 MapperScan 拉入且无数据源、测试根目录属性为空。显式 mock Mapper 并以工作目录定位仓库根后，9/9 通过；随后加入隔离回归，最终 11/11。
- 首次 ai adapter/config：发现 14 项，13 passed / 1 failure / 0 errors / 0 skipped；测试少算一次下载后的不可变快照 HTTP 请求。修正预期 3→4 后 14/14。
- 首次含既有 `FileApplicationTests` 的 file-service 组合：发现 11 项，10 passed / 0 failures / 1 error / 0 skipped；既有测试包名无法定位 `@SpringBootConfiguration`，与仓库既有测试报告一致。改成应用入口注解烟测后最终 11/11；没有把真实外部服务写成已启动。

## 7. 外部依赖真实运行状态

| 依赖 | 状态 | 说明 |
| --- | --- | --- |
| 真实 MySQL 8 / V002 实际迁移 | `NOT_RUN` | 迁移与约束做源码/服务契约测试；未连接真实数据库 |
| 真实 file-service 进程 | `NOT_RUN` | 使用 Spring MockMvc，不是独立进程 |
| 真实 MinIO | `NOT_RUN` | 对象存储使用内存替身；生产 MinIO client 仅编译 |
| ai-service → 真实 file-service HTTP | `NOT_RUN` | 使用 JDK synthetic HTTP server 验证协议、Bearer、404/503 和摘要；不是实际服务进程 |
| 真实 document-service / OCR | `NOT_RUN` | 明确不在本任务范围 |
| 真实远程模型 | `NOT_RUN` | 明确不在本任务范围 |
| 真实浏览器 / UI | `NOT_RUN` | P-09 未决定且不在本任务范围 |

## 8. 剩余风险与人工事项

1. 既有数据库必须在部署新代码前由数据库所有者执行 `V002__owner_aware_file_access.sql`；新建数据库继续由更新后的 `file.sql` 建表。没有在未验证的真实 MySQL 上宣称迁移成功。
2. 历史 owner-null 记录一律不可由新链读取。若必须迁移，需逐项取得可信 owner 证据并另建受控迁移；本提交禁止自动或猜测回填。
3. 旧 permitAll 文件接口对历史 owner-null 文件仍是不安全遗留面；本任务只确保新 owned 记录不会被旧 metadata/list/search 看到，未重构旧接口。
4. ai-service 当前只转发正在处理的真实用户 HTTP Bearer；无请求上下文的异步字节读取按冻结语义返回 503。当前材料附加/下载在请求链内；若未来给 Worker 增加文件字节读取，需在既有安全模型内单独建设有审计的短期 delegation，不得保存用户 token 或信任内部 header。
5. 真正的 MinIO 网络失败、对象缺失、MySQL CHECK/索引行为、双服务 JWT、多用户 HTTP 和长流中断仍待真实环境验收。
6. `OfficialBasisContentProvider`、本地候选 extractor、预览 parser/redactor 仍按 M0-06 既有边界 fail closed；它们属于文档/OCR/提取后续任务，不在本任务中越界处理。

## 9. 集成顺序与复跑

1. 在目标数据库备份和变更窗口内执行 `file-service/src/main/resources/db/file-service/V002__owner_aware_file_access.sql`；确认所有历史行 `owner_id` 仍为 NULL，不执行推断回填。
2. cherry-pick 本交接提交，构建并部署 file-service，再部署 ai-service；不要只部署 ai adapter 而遗漏 file-service API/迁移。
3. 用两个真实用户 JWT 分别走一次 owner-aware 上传、metadata、content；确认同 owner 200、异主/不存在相同 404、只带 `X-User-Id` 为 401，并核对下载 SHA-256。
4. 复跑第 5 节的 file-service、adapter、M0-04、M0-06、compile、默认 asset gate 和 `git diff --check`。
5. 本提交不修改 M0-07 selector/acceptance 资产；最终 milestone 集成任务应在真实链和其他缺口闭合后再决定 Integrated selector，不能由本任务制造 `AUTO_VERIFIED`。
