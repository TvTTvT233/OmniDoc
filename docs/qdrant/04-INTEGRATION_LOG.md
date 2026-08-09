# Qdrant 向量后端接入：集成日志

## 当前状态

`PARALLEL_DEVELOPMENT`

QV00 已冻结目标、架构、Java 契约、配置名称、任务边界和验收规则；QV01-QV04 已从同一交付提交创建并进入并行开发，尚未集成，Qdrant 尚未接入主运行链路。

## 共同基线

- QV00 起始分支：`docs/bootstrap-project-context`；
- QV00 起始 HEAD：`99ed70c`；
- QV00 起始工作区：干净；
- QV01-QV04 共同基线：`3ec7a0fb629aac3d526ac03058b577b71f921a2b`。

## 并行任务启动记录

| 编号 | 分支 | 工作树 | Codex 任务 ID | 启动状态 |
| --- | --- | --- | --- | --- |
| QV01 | `feature/qdrant-local-baseline` | `../.codex-worktrees/qdrant-qv01` | `019fe74e-9712-70a0-8f1f-9e4e20802ae3` | `IN_PROGRESS` |
| QV02 | `feature/qdrant-vector-backend` | `../.codex-worktrees/qdrant-qv02` | `019fe74e-b7a1-76f2-b887-8eba2e000012` | `IN_PROGRESS` |
| QV03 | `feature/qdrant-shadow-migration` | `../.codex-worktrees/qdrant-qv03` | `019fe74e-e464-7da2-9bfa-84856551ed14` | `IN_PROGRESS` |
| QV04 | `test/qdrant-vector-acceptance` | `../.codex-worktrees/qdrant-qv04` | `019fe74f-032d-7591-a93c-380b6724cb9b` | `IN_PROGRESS` |

四个任务均已核验分支、共同基线和初始干净工作区。专项任务只更新自身 handoff；主协调任务负责后续状态回填、按 `QV01 -> QV02 -> QV03 -> QV04` 集成和统一门禁。

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
