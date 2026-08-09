# Qdrant 向量后端接入：集成日志

## 当前状态

`PENDING_TASK_CREATION`

QV00 已冻结目标、架构、Java 契约、配置名称、任务边界和验收规则；QV01-QV04 尚未创建，Qdrant 尚未接入运行链路。

## 共同基线

- QV00 起始分支：`docs/bootstrap-project-context`；
- QV00 起始 HEAD：`99ed70c`；
- QV00 起始工作区：干净；
- QV01-QV04 共同基线：待 QV00 交付提交完成后记录。

## 预定集成顺序

```text
QV01 -> QV02 -> QV03 -> QV04
```

主协调任务在每次集成后记录：

- 来源分支和提交；
- 冲突文件及解决方式；
- 契约变化；
- 实际测试；
- 剩余风险。

## 已知基线缺陷

### 覆盖与删除存在旧 HNSW 命中

QV00 特征测试确认：相同逻辑 ID 覆盖写入后，当前 `hnswlib-core` 调用方式仍可能返回旧向量；后续删除也可能留下旧版本命中。

- QV00 不修改生产实现；
- 两项对应回归测试已保留并明确禁用；
- QV01 必须修复后启用两项测试；
- 未修复前不得把 baseline 幂等 upsert 和删除语义写成已验证。

## 尚未验证

- Qdrant Docker 启动和健康检查；
- 官方 Java Client 连接；
- Collection、alias、payload index；
- 服务端多租户过滤；
- Shadow 与迁移；
- 两个 AI 客户端的一致可见性；
- 真实 Embedding/RAG 闭环；
- QV01-QV04 集成。
