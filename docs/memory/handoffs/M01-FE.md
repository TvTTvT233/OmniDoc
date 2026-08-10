# Memory M01-FE 前端基线交接

## 1. 交付状态

```text
HANDOFF_READY / CURRENT_BEHAVIOR_VERIFIED
```

- 分支：`test/memory-m01-frontend`
- 基线：`37f81f7495f16c304b2b7b04dab1ab7783b67cae`
- 范围：前端 Conversation Memory 当前行为基线
- 生产行为：未修改

本交付只证明现有前端会话行为和已知缺口，不声明 Memory V1 目标已经实现，也不连接真实后端、模型、MySQL、Redis 或浏览器端到端环境。

## 2. 已建立的测试基础

- 增加 Vitest 0.34、Vue Test Utils 2 和 jsdom 22；
- 增加独立脚本 `npm.cmd run test:memory-baseline`；
- 测试只匹配 `*.memory-baseline.test.js`，不会把现有 Node 内置测试误交给 Vitest；
- 使用单线程 jsdom，避免模块级单例状态和 `localStorage` 基线在并发 worker 间产生噪声；
- 三个基线测试文件全部通过真实模块导入或 Vue 组件挂载验证，没有使用静态源文本断言。

## 3. 已证明的当前行为

| 行为 | 证据类型 | 结果 |
| --- | --- | --- |
| 刷新后只恢复 `global_conv_id`，不恢复消息 | `useGlobalAi` 模块重载 | 已复现 |
| `resetConversation` 只清本地单例和存储指针 | composable 行为 | 已复现 |
| 全局助理挂载不拉历史，也不立即创建会话 | 组件挂载 + API mock | 已复现 |
| 后端创建失败后使用 `temp_*` 调用 Agent | 输入交互 + API mock | 已复现 |
| 编辑器“新话题”清 UI 后继续复用旧 ID | 组件点击、发送 + API mock | 已复现 |
| 编辑器创建失败后使用 `default_conv` | 组件挂载、发送 + API mock | 已复现 |
| 全局助理与编辑器各自使用不同活跃 ID | 双组件挂载、分别发送 | 已复现 |

这些用例是 M01 的“现状证明”。M06 修复对应行为时，相关断言应被目标契约测试替换或反转，不能把“继续保留缺陷”当作长期回归要求。

## 4. 文件

- `web/vitest.config.js`
- `web/src/utils/useGlobalAi.memory-baseline.test.js`
- `web/src/views/components/GlobalAiAssistant.memory-baseline.test.js`
- `web/src/views/editor/EditorView.memory-baseline.test.js`
- `web/package.json`
- `web/package-lock.json`
- `docs/PROGRESS.md`
- `docs/memory/handoffs/M01-FE.md`

未修改 `useGlobalAi.js`、`GlobalAiAssistant.vue`、`EditorView.vue` 或其他生产 Vue/JS 文件。

## 5. 验证结果

在 `web` 目录执行：

```powershell
node --test src/api/asyncJob.test.mjs
npm.cmd run test:memory-baseline
npm.cmd run build
```

结果：

- 现有 Node 测试：2/2 通过；
- M01-FE Memory 基线：3 个文件、7/7 通过；
- Vite 生产构建：通过，1670 个模块完成转换；
- 未运行真实浏览器、真实后端或外部服务。

## 6. 剩余风险

- 测试通过 API mock 刻画前端行为，不证明后端错误响应、鉴权、分页或持久化已经联调；
- 当前没有浏览器 E2E 测试，刷新与双入口通过模块重载和组件挂载确定性模拟；
- 构建仍报告主 JS chunk 超过 500 kB，该问题不是本任务引入，也不在 M01 范围内；
- `npm install` 报告 12 个依赖审计问题（3 moderate、7 high、2 critical），本任务没有执行可能造成破坏性升级的 `npm audit fix --force`；需要独立依赖治理确认实际可达性；
- `temp_*`、`default_conv`、不恢复历史和双入口状态分裂均仍存在于生产代码，按冻结计划留给 M06；
- M02/M03/M05 未完成前，无法执行结构化历史、归档、恢复、永久删除和摘要后的完整前端验收。

## 7. 后续接入

M06 应从统一 Conversation API/DTO 建立目标测试，并至少验证：按用户和入口隔离活跃指针、刷新分页恢复、先创建后切换、失败保留旧会话与草稿、禁止临时 ID、归档/恢复和永久删除。M07 最终门禁不得保留任何 Memory 目标跳过项。
