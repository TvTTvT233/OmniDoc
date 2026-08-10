# Memory V1 验收入口

本目录在 M01 阶段只验证合成 fixture、Summary JSON Schema、报告 JSON Schema、资产契约测试和敏感信息边界，不修改或替代 Memory V1 生产实现。

## 资产自检

在仓库根目录执行：

```powershell
.\scripts\memory\run-m07-acceptance.cmd
```

成功状态只能是：

```text
ASSET_VERIFIED / PENDING_INTEGRATION
```

脚本生成：

```text
ai-service/target/memory-v1-acceptance/memory-v1-report.json
```

报告只包含测试计数、环境边界和未评估门禁，不复制消息、摘要、文档、Prompt、选区或工具结果正文。资产自检不连接 MySQL、Redis、真实模型、远程 Embedding 或 Qdrant。

## 集成门禁

```powershell
.\scripts\memory\run-m07-acceptance.cmd -Integrated
```

M02-M06 生产源组或 `MemoryV1IntegratedGateTest` 缺失时，入口以退出码 `2` 输出 `PENDING_INTEGRATION`，不会把缺失实现计作通过。

M01-AC 版本即使发现候选集成资产也没有权限输出 `AUTO_VERIFIED`。最终状态必须由 M07 在全部 P0/P1 门禁、回归和清理检查完成后实现并复核。
