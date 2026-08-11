# Memory M06-C 独立前端验收交接

## 1. 状态与边界

```text
PRE_INTEGRATION_FAILED / PENDING_INTEGRATION
```

- 分支：`test/memory-m06c-acceptance`
- 起始 HEAD：`6ebdb2f523c30681097745d914bd7f8bff9f841d`
- 交付提交：本文件所在提交
- 生产实现：未修改
- 测试跳过：0

本交付只冻结并执行 M06-C 前端目标验收。起始基线尚未包含主任务的 M06-C UI 接管，因此目标测试应失败且实际全部失败；这不是回归通过，也不能标记为 `STAGE_VERIFIED`、`AUTO_VERIFIED` 或 `HUMAN_ACCEPTED`。

委派中引用的 `docs/memory/00-GOAL_AND_SCOPE.md`、`01-ARCHITECTURE.md`、`02-CONTRACT.md`、`03-TASK_BOARD.md` 在起始提交中不存在。本任务完整阅读了仓库实际对应的 `00-MEMORY_V1_SPEC.md`、`01-TASK_BOARD.md`、`02-API_AND_STORAGE_CONTRACT.md`、`02-PARALLEL_DELIVERY_PLAN.md`、`03-AUTO_ACCEPTANCE.md` 和 `04-HUMAN_ACCEPTANCE.md`，并以这些冻结文件为依据；没有补造或改名公共文档。

## 2. 交付内容

- `web/src/views/components/GlobalAiAssistant.memory-ui.test.js`
  - 刷新后从按用户、入口隔离的真实 Conversation 指针恢复；
  - 使用结构化 Message Page，并以 `beforeSequence` 加载更早历史；
  - 公开投影得到空页但 `hasMore=true` 时继续沿 `nextBeforeSequence` 翻页；
  - 新建请求完成前保留旧会话，成功后才切换；
  - 创建失败不调用 Agent、保留草稿、不生成 fallback ID；
  - Agent 失败保留草稿，重试复用 UUID `clientRequestId`；
  - 禁止 `temp_*`、`default_conv` 和伪 assistant 成功回复。
- `web/src/views/editor/EditorView.memory-ui.test.js`
  - 使用共享结构化历史列表，在已有会话间切换并创建新会话；
  - 归档、查看/恢复归档会话、二次确认永久删除；
  - 删除活跃会话后清理消息和本地活跃指针；
  - Agent 执行期间禁用新建与历史切换，避免响应写入错误会话。
- `web/vitest.memory-ui.config.js`
  - 只发现 `src/**/*.memory-ui.test.js`；jsdom、单线程、自动清理 mock。
- `web/package.json`
  - 新增 `npm.cmd run test:memory-ui`，没有改依赖和锁文件。

测试优先使用 `data-testid`，同时接受现有可访问中文文本、`title`、`aria-label` 和已有类名。建议集成实现提供稳定的 `conversation-new`、`conversation-load-older`、`conversation-archive`、`conversation-filter-archived`、`conversation-restore`、`conversation-delete` 及 `conversation-item-<id>` 测试标识；这些标识不改变业务契约，但可避免图标或文案调整使行为验收失真。

## 3. M01 baseline 的反转关系

既有 `*.memory-baseline.test.js` 是 M01 的历史证据，当前仍以 7/7 通过证明以下旧缺陷存在：只恢复单例 ID、不恢复消息，创建失败使用 `temp_*`，编辑器复用旧 ID/使用 `default_conv`，以及两个入口状态实现分裂。

本交付没有写权限修改这些历史文件，而是用 11 个 `*.memory-ui.test.js` 目标用例逐项反转其产品断言。M06-C 集成后，最终门禁应运行 `test:memory-ui`；不得继续把 M01 缺陷基线的通过计入 Memory V1 目标通过。历史基线如需保留，应明确标记为审计资产并从最终成功计数中排除。

## 4. 实际执行结果

### M06-C 目标验收

```powershell
cd web
npm.cmd run test:memory-ui -- --reporter=dot
```

结果：2 个测试文件失败；11 项发现，0 通过、11 失败、0 跳过；退出码 1。

当前失败均与预集成缺口一致：

| 目标 | 当前失败证据 |
| --- | --- |
| 全局刷新恢复与消息分页 | 未调用结构化 `getConversation/listMessages` |
| 空公开投影页继续分页 | 没有结构化消息分页控件或 `nextBeforeSequence` 编排 |
| 全局新建成功后切换 | 未调用 `conversationApi.createConversation` |
| 创建失败保留草稿 | 仍走旧 `startChat`，未使用结构化创建流程 |
| `clientRequestId` 与失败重试 | Agent payload 没有 `clientRequestId` |
| 禁止 fallback ID | 未通过结构化 API 取得 durable ID |
| 编辑器共享历史与切换 | 未调用 `listConversations`，没有真实历史项 |
| 归档 | 缺少 Conversation 归档操作 |
| 恢复 | 缺少归档列表/恢复操作 |
| 永久删除 | 缺少 Conversation 二次确认删除操作 |
| 执行中切换竞态 | 没有真实历史项，也没有禁用切换状态 |

结论严格保持：

```text
PRE_INTEGRATION_FAILED / PENDING_INTEGRATION
```

### 可运行既有回归

```powershell
npm.cmd run test:memory-contract -- --reporter=dot
npm.cmd run test:memory-baseline -- --reporter=dot
node --test src/api/asyncJob.test.mjs
npm.cmd run build
```

结果：

- M06-A 前端结构化契约：14/14 通过，Failures 0、Skipped 0；
- M01 历史缺陷基线：7/7 通过，Failures 0、Skipped 0；该结果不属于 M06-C 目标通过；
- 既有 async job Node 测试：2/2 通过，Failures 0、Skipped 0；
- Vite 生产构建：通过，1670 个模块完成转换；保留既有主 chunk 超过 500 kB 警告。

可执行自动测试合计为 34 项：23 项既有测试通过，11 项 M06-C 目标测试按预期失败，0 项跳过。不得将“23 通过”写成 M06-C 通过。

依赖准备使用：

```powershell
npm.cmd ci --ignore-scripts
```

安装成功；审计仍报告既有 12 个问题（3 moderate、7 high、2 critical），本任务没有执行破坏性依赖升级。

## 5. 待集成项

主任务合入 M06-C 生产实现后，需要逐项把上述 10 个目标变为通过，并至少满足：

1. 全局助理和编辑器都从认证 `userId + entry` 的会话状态恢复，不再读取 `global_conv_id` 或本地模块单例作为事实；
2. 两个入口都通过 `conversationApi` 读取 Conversation 和分页 Message，正确拼接更早页且保持 sequence 顺序；
3. 新建、历史切换、归档、恢复和永久删除都在 Agent 未执行时进行，执行期间控件显式禁用且处理器拒绝竞态切换；
4. 没有活跃会话时先成功创建 durable Conversation，再发送 Agent 请求；创建失败保留输入并停止发送；
5. 每个用户提交包含 UUID `clientRequestId`，失败重试复用，成功后才清草稿；
6. 失败只显示错误状态，不追加伪造的 ASSISTANT 成功内容；
7. 永久删除必须二次确认，成功后清除本地活跃指针、当前消息和对应缓存；
8. 集成后重跑本文件第 4 节全部命令，并以 `test:memory-ui` 的 11/11、Skipped 0 作为 M06-C 自动目标门槛。

## 6. 风险与未验证边界

- 组件验收使用结构化 API/Agent mock，证明前端编排契约，不证明真实后端、JWT、MySQL、Redis 或模型已联调；
- jsdom 的模块重载、存储和交互用于确定性刷新模拟，不等同于真实浏览器刷新、跨标签页或路由 E2E；
- 确定性 mock 已覆盖 M06-B 所述“公开投影后空页但 `hasMore=true`”的继续翻页，但尚未通过真实后端数据验证；
- 未验证真实多用户隔离、游标签名跨重启、多副本共享密钥、真实服务重启和永久删除后端残留为零；
- 构建的大 chunk 警告和 npm 依赖漏洞是既有风险，未因本验收任务改变；
- 在目标 11/11 通过且完成真实联调前，M06-C 必须保持 `PENDING_INTEGRATION`。
