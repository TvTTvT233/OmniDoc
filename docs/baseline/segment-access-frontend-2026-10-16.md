# 分段访问状态前端修复与验收

开始日期：2026-10-16；收尾及真实浏览器验收日期：2026-10-17。起始提交：`05c2d63`，分支 `integration/core-baseline`。本记录仅覆盖分段读取的前端状态、相关回归及真实浏览器验证，不代表索引写入、Embedding 或 RAG 问答通过。

## 修改边界

- `web/src/views/editor/EditorView.vue`：分段读取接受显式 `data.indexState + data.segments`，成功状态仅有 `NOT_INDEXED` 与 `SEGMENTS_AVAILABLE`。
- 仅服务端明确 `NOT_INDEXED` 且分段为空时显示“当前文档尚未索引”；该提示不是权限降级，也不是吞掉错误后显示空结果。
- `SEGMENTS_AVAILABLE` 只表示分段可读，前端展示返回的内容和数量，不将其显示为向量化完成。该状态却无片段、片段内容类型异常、状态缺失或未知时均显示结果未知。
- 兼容旧服务的分段数组。旧非空数组展示实际片段；旧空数组说明“未返回分段，接口未提供索引状态”，不推断未索引。
- HTTP 401/403/404/409/503 分别显示重新登录、无权读取、文档不存在或无权访问、分段数据不完整或暂不可读、服务暂不可用。409 不承诺整个向量索引损坏；错误后清除先前片段与状态，保留重试入口，不改写正文。
- 不改全局请求拦截器、不新增自动索引、不调用外部模型、不修改旧工作台标记。

测试文件为 `web/src/views/editor/EditorView.baseline-state.test.js` 和 `web/src/api/indexing.baseline-state.test.js`。沿用 `npm run test:baseline-state` 两个独立进程，真实 startup 测试仍与 shallowMount 隔离，没有改动其断言。

## 已执行自动验证

以下在 `D:\OmniDoc\web` 执行，证据目录为 `.local/baseline-evidence/segment-access-2026-10-16/`。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `npm run test:baseline-state` | 33/33 + 独立 startup 1/1 通过 | `frontend-state.log` |
| `node node_modules/vitest/vitest.mjs run --config vitest.baseline-state.config.js` | 最后调整 409 文案后 33/33 通过 | `frontend-state-final.log`；该日志不包含 startup，startup 仍以此前独立进程结果为准 |
| `node node_modules/vitest/vitest.mjs run --config vitest.memory-ui.config.js` | 11/11 通过 | `frontend-memory-ui.log` |
| `node node_modules/vite/bin/vite.js build` | 退出 0，保留既有 >500 kB chunk 警告 | `frontend-build.log` |
| `git diff --check -- web` | 通过；Git 提示测试文件未来归一 CRLF，不是检查失败 | 工具执行记录 |

33 项包含本轮新增 21 项和已有 12 项；独立 startup 1 项及既有 Memory UI 11 项另列，合计本轮执行 45 项，不能等同于 45 条真实端到端用例。

新增回归覆盖：明确未索引、真实分段返回、旧空数组不可推断状态、HTTP 401/403/404/409/503 不得转成未索引、矛盾/异常响应、服务失败后重试恢复，以及真实 API 封装/响应拦截器保留成功状态和错误拒绝。API 测试使用模拟 Axios adapter；组件测试使用 jsdom 与模拟 API。

首次运行曾有 32/33 通过、1 个测试失败：测试尝试查询不存在的 `el-empty-stub` 标签。已改为显式渲染空态组件的 description 并断言可见文本；没有改弱产品断言，也没有为测试修改生产逻辑。失败原日志保留为 `frontend-state-initial-test-selector-failure.log`。

## 真实浏览器验证（2026-10-17）

使用本机已安装 Microsoft Edge，Playwright 启动独立 headless Chromium，1440×1000。前端为 `http://127.0.0.1:5173` Vite 开发模式，代理连接本轮运行任务重启后的真实本地服务；没有 HTTP mock，没有修改浏览器认证数据绕过登录。通过真实登录表单分别登录合成所有者账号与另一合成账号，打开编辑器并点击“知识分段”。

| 场景 | 真实响应与界面结果 | 结论 |
| --- | --- | --- |
| 所有者读取没有索引记录的已有文档 | HTTP 200 / code 200，`NOT_INDEXED`、空分段；明确显示“当前文档尚未索引”，正文保持，未提交索引 | 通过 |
| 所有者读取已准备好的两段合成片段 | HTTP 200 / code 200，`SEGMENTS_AVAILABLE`、2 段；界面显示数量，展开首段显示正确文本，响应两段与夹具逐段一致 | 读取展示通过；夹具为手工 Redis 数据，没有 Embedding 或向量写入，不是索引创建成功 |
| 另一账号读取上述文档分段 | HTTP 404 / code 404；界面显示“文档不存在或无权访问，无法读取分段”，没有显示未索引、没有泄漏片段，保存按钮禁用 | 通过 |

三条真实浏览器结果为 **3/3**。记录在 `frontend-browser-20261017.json`；截图为 `browser-not-indexed-20261017.png`、`browser-segments-available-20261017.png`、`browser-access-denied-20261017.png`。证据只包含合成内容和响应状态，无密码或 JWT。

拒绝提示检查首次使用 exact 文本匹配时超时，因为同一段落还包含“重试加载分段”按钮文本；当时真实 HTTP 已返回 404、提示也已显示。随后按实际 DOM 文本使用包含匹配确认，并保留同一次响应证据，没有修改页面内容或生产行为来绕过验收。

浏览器仅执行上述三条，不声称真实 UI 已逐项验证 401/403/409/503。它们的前端状态由模拟组件/真实拦截器测试覆盖；运行任务的真实 HTTP 矩阵与停机故障验证另行记录，不能混计为浏览器用例。

## 收尾与限制

- 未调用索引 POST、Embedding 或外部模型；界面自动摘要/关键词在后端禁用模型配置下失败，不计作模型验证。
- 不验证生产静态托管、多用户并发编辑或文档与索引版本一致性；本轮只修复分段读取及其状态展示。
- 409 文案最终为“分段数据不完整或暂不可读，请稍后重试”，不把合法全文向量但没有分段的情况说成整个索引损坏。该改动后重跑了 33 项专项和最终构建，已有未变更的 Memory UI / startup 不重复。
- 本轮独立浏览器已关闭；Vite 最终 PID 40120 经核对可执行路径及命令后已停止。后台五个服务保持原状，由运行任务统一记录。
- 已通知运行任务可清理本轮四个手工 Redis 夹具键；清理实际结果以运行任务的最终记录为准。用户原始文件未操作，仅使用合成样例。
- 未提交、合并或推送；本次前端变更限于 Editor 分段区、两个既有专项测试文件及本文档。
