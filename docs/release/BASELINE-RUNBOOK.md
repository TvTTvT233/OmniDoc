# 集成基线运行与迁移说明

适用代码基线：`83b9e80` 及其发布整理提交。此文依据现有源码和历史验收记录整理；本次发布准备没有重新执行全新机器安装或生产部署。

## 运行入口

需要 JDK 17、Node/npm、Docker Desktop 和 PowerShell。Windows 上可从项目根目录执行：

```powershell
# 仅首次初始化；已有 runtime.env 时脚本拒绝覆盖。
./scripts/baseline/Initialize-Runtime.ps1
docker compose --env-file .local/baseline-runtime/runtime.env -f scripts/baseline/compose.yml up -d
./mvnw.cmd -s .mvn/settings-local.xml -DskipTests package
```

该基础设施与旧 `docker-compose.local.yml` 是不同入口，不要混用。基线端口：MySQL 13306、Redis 16379、RabbitMQ 15673/15674、MinIO 19000/19001；后端 8080–8084，前端 5173。Qdrant 是可选 profile，本轮使用 baseline 向量路径。

初始化脚本在 Git 忽略的 `.local/baseline-runtime/runtime.env` 生成随机本地凭据。不要输出该文件，不要将密码放进命令行、截图、PR 或报告。代码、SQL 和配置模板可以公开，真实运行文件、数据库备份、用户材料不能公开。

## 数据库与功能开关

启动现有数据库前先做本地备份。不要对已有数据库重复执行根目录历史初始化 SQL 或清空 Docker 卷。基础设施初始化挂载仅对新建 MySQL 数据目录生效。

| 模块 | 配置/迁移 |
| --- | --- |
| 会话 Memory | `ai.memory.enabled=true`；首次允许 `ai.memory.schema.auto-migration.enabled=true`，迁移 `db/memory/V001`、`V002` |
| Review M0 | `ai.review-m0.persistence.enabled=true`；首次允许 `ai.review-m0.persistence.schema.auto-migration.enabled=true`，迁移 `db/review-m0/V001`–`V003`，启用后后台 poller 领取任务 |
| Agent 恢复 | `ai.agent.recovery.enabled=true`；首次允许 `ai.agent.recovery.schema-auto-migration=true`，独立 `db/task-runtime/V001__agent_run_operation.sql` |
| 文档一致性 | document-service 的 `document.schema.auto-migration.enabled` 默认 true，初始化器补文档、版本、快照和索引任务相关结构 |
| 文件归属 | 检查 `file_metadata` 实际结构，再按需应用 file-service 的 `db/file-service/V002__owner_aware_file_access.sql`；该 ALTER 脚本不能盲目重复执行，旧空 owner 文件不自动授予新用户 |

迁移完成并核实表结构后，将 Memory、Review、Agent 的 auto-migration 关闭；保留功能 enabled。Agent 开关重复或无效会拒绝启动。旧 Redis Agent 任务不会自动成为可恢复任务；不要将旧队列恢复与新 JDBC 任务混为一谈。

## 私有配置连接各服务

本机运行时，AI 使用 `document.service.url=http://127.0.0.1:8084` 和 `ai.review-m0.runtime.file-service-base-uri=http://127.0.0.1:8082`。内部调用需要两组独立随机值：

- `omnidoc.index.internal-token`：AI 与 document-service 使用同一值。
- `omnidoc.review.internal-token`：AI 与 file-service 使用同一值。

不要填写示例固定口令。用本机密码管理器或加密随机源生成，再保存在 `.local` 下的私有 YAML 或环境文件；五服务 JWT 配置也需一致。不要把用户 JWT 保存为后台长期凭据。

现有 `scripts/baseline/Start-Services.ps1` 支持 `-JavaPath`、`-Services`、`-ProviderEnvFile`、`-NoExternalModels`；它默认不启动 AI。`ProviderEnvFile` 只是导入本机环境变量，不代表必须提供外部模型密钥。该旧入口不会自行补齐上表所有新开关。

如使用私有 YAML，为各服务加载该文件，并保持 `local` profile；AI 的 `spring.config.additional-location` 同时按顺序加载私有 YAML、`scripts/baseline/no-external-models.yml`。只传一份逗号分隔的配置路径列表，不要重复同名启动参数。路径仅指向本机文件，不把密钥值拼进参数。

`no-external-models.yml` 禁用当前模型、摘要与 MCP；同时清除继承的模型密钥及相冲突的模型启用环境变量。启动后必须检查 `/api/ai/models` 中没有可用模型，不能仅凭进程启动断言禁用生效。此模式可以验证文本统计、审批/取消和手工 Review；真实摘要、生成、Embedding 和 RAG 答案不可用，不会生成假成功结果。

前端在 `web` 下运行 `npm ci`、`npm run dev -- --host 127.0.0.1`，通过网关 8080 访问服务。日常入口为 `/dashboard`、`/review-m0`、`/agent-workbench`。

## 启动后的验收与停止

1. 等待五服务真正启动完成，确认监听归属；启动脚本返回 PID 不等于 HTTP 就绪。
2. 用本地测试账号检查登录、文档列表、Review 手工流程及 Agent 文本统计。
3. 同一 `clientRequestId` 的同载荷应复用任务；不同载荷应冲突。删除任务必须停在审批阶段，可通过拒绝验证取消，不需要删除真实文件。
4. 用合成任务验证重启后历史和审批状态；生成写入提案需用户明确应用。未知写结果须人工核对，不自动重试。
5. 使用与启动入口对应的停止脚本。`scripts/baseline/Stop-Services.ps1` 只处理自身 manifest 记录且身份匹配的进程；不要用端口盲杀服务，不要通过 `down -v` 清理持久数据。

本机历史验收使用的 `.local/recovery-evidence` 脚本含私有环境依赖，不随发布提供，也不宣称上述整理已经构成一键完整安装器。自动回归命令和真实运行边界见 [V2 验收报告](../task-runtime/RESULT-2026-11-02.md)。
