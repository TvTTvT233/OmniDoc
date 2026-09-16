# QV04 验收入口

本目录只使用合成 fixture，不调用远程 Embedding，也不包含真实用户材料或密钥。

## 独立分支资产自检

```powershell
.\scripts\qdrant\run-qv04-acceptance.cmd
```

该模式验证 60 段 fixture、验收驱动、指标、报告结构、当前分支的 Agent/RAG/MCP 回归、静态敏感信息和 Collection 前缀边界。成功只表示 `ASSET_VERIFIED`；输出仍会明确显示 `PENDING_INTEGRATION`。

## 集成门禁

主协调任务合入 QV01-QV03 后，新增一个测试适配类实现 `Qv04IntegratedAcceptanceProvider`，再执行：

```powershell
$env:QV04_ACCEPTANCE_PROVIDER_CLASS = 'com.example.OmniDocQv04IntegratedProvider'
.\scripts\qdrant\run-qv04-acceptance.cmd -Integrated
```

集成适配器必须连接受控本地 Qdrant 或测试容器，并提供：

- 本地与 Qdrant 两个真实 `VectorBackend`；
- 两个独立客户端和真实重启生命周期；
- `baseline`、`shadow`、`qdrant` 模式观测；
- Shadow 成功与注入失败观测；
- dry-run、失败 checkpoint、恢复和幂等重跑的迁移观测。

缺少任一生产源组或适配器时，入口以退出码 `2` 报告 `PENDING_INTEGRATION`，不会把缺失实现计为通过。实际指标和运行环境写入 `ai-service/target/qv04-acceptance/`。

所有测试 Collection 和 alias 必须以 `qv04_test_` 开头。任何删除或清理逻辑都应再次调用同等边界检查；本入口本身不删除 Collection。
