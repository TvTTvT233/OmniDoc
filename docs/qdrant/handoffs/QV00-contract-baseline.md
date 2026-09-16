# QV00 交接：契约、基线与任务拆分

## 状态

`HANDOFF_READY`

Qdrant 目标、架构、公共 Java 契约、配置名称、任务边界和验收规则已经冻结，可以从包含本文件的 QV00 提交创建 QV01-QV04 独立任务。

## 基线

- 分支：`docs/bootstrap-project-context`；
- QV00 起始 HEAD：`99ed70c`；
- QV00 起始工作区：干净；
- QV01-QV04 共同基线：包含本交接文件的 QV00 交付提交，以创建工作树时记录的完整提交为准。

## 完成内容

- 新增 `docs/qdrant/` 目标、架构、冻结契约、任务看板、集成日志、自动测试和人工验收文档；
- 新增供应商无关的 `rag.vector.contract` Java 边界；
- 冻结 `baseline`、`shadow`、`qdrant` 模式；
- 冻结强制多租户过滤、Embedding 规格、迁移分页和错误语义；
- 在 application 配置中加入默认关闭 Qdrant 的配置骨架；
- 新增确定性契约测试和现有 HNSW 特征测试；
- 记录覆盖未替换旧向量、后续删除仍可能残留 HNSW 命中的已知缺陷。

## 已知缺陷

当前本地 HNSW 对相同 ID 执行覆盖写入时可能仍返回旧向量，后续删除也可能返回旧版本命中。QV00 没有修改生产实现；两项测试已用 `@Disabled` 明确登记，QV01 必须修复并全部启用。

## 未实现和未验证

- QV01-QV04 尚未创建；
- 现有 `VectorStore` 尚未实现新契约；
- 未引入 Qdrant SDK 或 Docker 服务；
- 未执行 Qdrant 连接、Collection、alias、过滤、迁移或 Shadow；
- 未调用真实 Embedding；
- 未执行完整外部基础设施端到端验收。

## 验证

- QV00 专项：10 个测试，8 个通过、0 个失败、0 个错误、2 个按已知缺陷跳过；
- 完整 `ai-service`：109 个测试，107 个通过、0 个失败、0 个错误、2 个按已知缺陷跳过，`BUILD SUCCESS`；
- 完整回归使用非真实占位配置，远程模型实际成功 0/4；
- 未连接真实 Redis、RabbitMQ、Embedding 或 Qdrant；
- Spring 上下文未创建 Qdrant Client，符合 QV00 和默认 `baseline` 边界；
- `git diff --cached --check` 通过；
- 静态敏感信息检查扫描 621 个文本文件，无命中。

## 下一步

主协调任务从 QV00 共同基线创建 QV01-QV04 的独立分支、工作树和 Codex 任务，并把任务 ID、工作树和基线提交回填到任务看板。
