# 前端真实状态修复与验证

更新日期：2026-10-15；真实浏览器验收日期：2026-10-14。起始候选：`integration/core-baseline` / `ca3423a`。本记录针对当前工作树的前端最小修复，不等于整个运行基线或真实服务验收通过。

## 范围与结果

- 编辑器文本分析失败不再用固定 80%/15% 生成统计；显示失败与重试，响应缺少统计字段也不能显示零值成功。
- 关键词失败不再返回固定示例词；摘要更新失败明确说明保留的是上次结果。
- 索引请求失败不再从正文切出两段示例；成功写入与读取分段失败分别展示。写入超时或异常说明“无法确认是否完成”，不冒充确定回滚。
- 分段策略只展示真实加载过的列表；首次加载失败禁用索引，后续加载失败明确标示保留旧选项。分段读取失败可单独重试，不重新提交索引。
- 编辑器和工作台模型列表失败不再制造 `available: true` 选项，显示可用性未知与重试。后端返回的 available 标记仍属于后端配置状态，不能据此证明真实模型已连通。
- 文档读取错误移到正文之外；失败保留现有正文，函数层保护保存、AI 写入、索引、文档任务和版本恢复。未成功读取时正文不可编辑，协作组件不挂载，避免保存错误提示或自动覆盖内容。
- 索引提取文本优先使用浏览器 `innerText`，保留可见段落边界；无布局的组件测试环境提供块级换行降级。
- 删除了首次加载重复请求关键词的无 catch 路径；所有本轮关键词请求由同一带失败状态的函数处理。
- 实际浏览器发现首次无登录态启动时，路由尚处于 `/`，全局助理提前挂载并抛出认证 scope 缺失异常；根组件初次 patch 中断后路由更新留下两个活跃 RouterView。`main.js` 等待 `router.isReady()` 后挂载，避免临时路由进入有认证要求的组件。
- 移除 `main.js` 对 `collaboration/autoInit` 的副作用导入。编辑器已有受文档加载状态约束的协作面板；保留这一唯一入口，避免另建不受状态约束的悬浮面板。

未新增业务功能，未修改依赖版本、锁文件、后端、部署配置或 Review M0；未提交、合并或推送。正文内容和源文件不被本轮异常处理改写。

## 修改文件

- `web/src/views/editor/EditorView.vue`
- `web/src/views/dashboard/IndexView.vue`
- `web/src/main.js`
- `web/src/main.startup.test.js`
- `web/src/views/editor/EditorView.baseline-state.test.js`
- `web/src/api/indexing.baseline-state.test.js`
- `web/vitest.baseline-state.config.js`
- `web/vitest.startup.config.js`
- `web/package.json`（仅增加专项测试命令，不改依赖版本）
- 本文档。

## 已执行验证

在 `D:\OmniDoc\web` 执行。Node 来自桌面随附运行时；本机无 npm PATH，临时使用 `%TEMP%\omnidoc-baseline-npm-10.9.2\package\bin\npm-cli.js`。依赖安装为 `npm ci --no-audit --no-fund`，284 个包，退出 0；未修改 package-lock。未执行依赖安全审计或升级。

| 命令 | 本次结果 | 证据范围 |
| --- | --- | --- |
| `npm run test:baseline-state` | 2026-10-15：两个独立进程 12/12 + 1/1 通过 | 10 个模拟接口的 Vue 组件用例 + 2 个真实 API 封装/响应拦截器配合模拟 HTTP adapter 用例；再独立运行 1 个真实异步 router 启动用例 |
| `node node_modules/vitest/vitest.mjs run --config vitest.startup.config.js`，再 `node node_modules/vitest/vitest.mjs run --config vitest.baseline-state.config.js` | 2026-10-15：反向独立进程 1/1 + 12/12 通过 | 顺序隔离复验；不重复计入测试总数 |
| `node node_modules/vitest/vitest.mjs run --config vitest.config.js` | 6/6 通过 | 既有 Memory 基线回归 |
| `node node_modules/vitest/vitest.mjs run --config vitest.memory-contract.config.js` | 25/25 通过 | 既有会话契约与 JWT 回归 |
| `node node_modules/vitest/vitest.mjs run --config vitest.memory-ui.config.js` | 11/11 通过 | 既有全局/编辑器会话 UI 组件回归 |
| `node --test src/api/asyncJob.test.mjs` | 2/2 通过 | 既有异步状态轮询回归 |
| `node node_modules/vite/bin/vite.js build` | 退出 0 | 生产资源构建；保留既有大于 500 kB chunk 警告 |
| `git diff --check -- web` | 通过 | 空白格式检查 |

前端测试合计 57 项通过，不把它们累加为真实端到端场景数。新增测试独立于原 Memory 门禁，原门禁的 42 项口径不变。

日志与浏览器证据位于忽略的本地目录 `.local/baseline-evidence/frontend/`，包含 `state-tests.log`、`startup-first.log`、`state-after-startup.log`、`memory-baseline.log`、`memory-contract.log`、`memory-ui.log`、`async-job.log`、`build.log`。最早 Vite 进程日志仍留在已忽略的 `artifacts/baseline/frontend/vite-runtime*.log`，最终重启的 Vite 日志已在 `.local`。

### 2026-10-15 测试隔离纠正

2026-10-14 最后一轮专项实际为 **12 通过、1 失败**，失败项为启动回归的 `loginSetups` 预期 1、实际 0；此前曾有一次 13/13，通过结果并不稳定，原文“最终 13/13”因此不成立。失败原日志已保留为 `state-tests.failed-2026-10-14.log`，不覆盖或抹去。

定位证据：同进程先执行 `shallowMount` 组件测试后，真实入口 DOM 出现 `<router-view-stub>`；单独运行入口测试显示真实 Login 组件。Vue Test Utils 的全局 VNode 替换影响了入口的真实 `createApp`，仅 `vi.resetModules()` 不能可靠消除，表现与文件运行顺序有关。现在将启动用例命名为 `main.startup.test.js`，使用独立 `vitest.startup.config.js` 和独立 Vitest 进程，不再与 shallowMount 共用运行进程；移除无效的 resetModules 修补，**保留全部原断言**，包括 Login setup 恰好一次、DOM 恰好一份、助理 setup 为零以及真实 app 已挂载。

2026-10-15 按“组件/接口 12 → 启动 1”和“启动 1 → 组件/接口 12”两个顺序分别完成独立进程运行，四个进程均退出 0。此次修正只涉及测试组织和命令，未改动已经真实浏览器复验通过的生产挂载逻辑。

## 新增回归覆盖

1. 分析失败不造数据，重试后展示真实统计。
2. 格式不完整的统计响应不能显示为成功。
3. 关键词/摘要失败不造数据，旧摘要明确标示。
4. 索引失败不造分片，重试后读取后端分片，正文保持。
5. 写入已确认但分片读取失败，分开显示且独立重试。
6. 模型/策略发现失败不造可用选项。
7. 首次读取失败不写入错误正文，保存/AI写入/索引被阻止，读取重试可恢复。
8. 后续读取失败保留尚未保存的 DOM 正文。
9. 索引提交保留浏览器可见段落边界。
10. 工作台模型发现失败不显示虚构可用模型。
11. HTTP 200 但业务 code 500 经真实响应拦截器拒绝，索引 Promise 不会 resolve 为成功。
12. 成功业务响应仍正常兼容。
13. 使用真实 Vue Router 的异步首次重定向，入口等待路由就绪，登录只挂载一次且不会先挂载要求认证的助理。

## 真实浏览器验收（2026-10-14）

浏览器为本机已安装 Microsoft Edge（Playwright 启动的独立 headless Chromium，1440×1000），前端为 Vite **开发模式** `http://127.0.0.1:5173`，代理接真实 Gateway 8080 和 AI 8083。未 mock、拦截或替换后端响应。测试仅使用运行任务创建的合成账号和 TXT 样例，凭据从忽略目录读取，未写入此文档或证据 JSON。所有聊天模型禁用，未配置 Embedding Key，未开展真实外部模型验收。

| 实际可见 UI 操作/检查 | 结果 |
| --- | --- |
| 全新无登录态打开登录页，再填写合成账号并登录 | 通过；修复后登录页面 1 个，未出现 `Authenticated conversation scope is unavailable` |
| 登录后等待真实文档列表、打开文档 | 通过；Dashboard 1 个、Editor 1 个、paper 1 个；修复前实际两个活跃 RouterView 分别在 y=0/1000，不是隐藏测试节点 |
| 页面文件输入上传 `baseline-browser-20261014.txt`，列表打开解析后正文 | 通过；两个原始段落显示；操作为实际上传/创建请求 |
| 编辑正文，点击“手动保存”，刷新后比较可见正文；新浏览器重新登录再读取 | 通过；新正文及段落换行完整保留 |
| 已登录时直接刷新编辑器 URL | 通过；只有 1 个编辑器和 1 个内嵌协作面板，`#__collaborate_panel__` 为 0 |
| 点击“新对话”，刷新恢复所选会话；归档后恢复 | 通过；真实 Conversation HTTP；仅验证会话元数据及活跃指针，不宣称模型消息历史恢复 |
| 打开不存在的合成文档 ID | 通过；独立错误提示，正文为空且不可编辑，保存按钮禁用，协作面板为 0；错误不会成为可保存正文 |
| 真实禁用模型环境下自动关键词/摘要请求失败 | 通过；显示错误，关键词没有固定示例；原正文仍在，已有摘要标示上次结果 |
| 为未索引文档打开分段管理、重试读取明细 | 真实后端 HTTP 200 / business code 403；前端显示“分段明细加载失败，当前结果未知”，未伪造切片或声称索引成功。此项是失败展示通过，不是 RAG 可用性通过 |

主要截图：`browser-duplicate-login.png`（修复前）、`browser-single-editor.png`、`browser-fixed-saved.png`、`browser-conversation-restored.png`、`browser-document-load-failure.png`、`browser-segment-load-failure.png`。`browser-checks.json` 保存后续 7 个检查的真假值，不包含认证信息；之前完成的登录/上传/单实例检查以工具执行记录与截图为证据，不伪造为同一自动脚本批次。

中途一次浏览器工具调用因等待隐藏的 option 可见超时，随后新开独立浏览器重新登录并验证已保存内容和会话状态；该超时不是产品通过证据。后续用实际选择框值等待完成验证。

## 运行与剩余边界

本轮 Vite 使用随附 Node 执行 `node node_modules/vite/bin/vite.js --host 127.0.0.1 --strictPort`，工作目录 `D:\OmniDoc\web`，以隐藏进程运行。最终测试进程 PID 18584 已停止；最终浏览器已关闭。重启可用上述命令；停止时核对监听 5173 的 PID、进程路径及任务归属后终止本次 Node 进程，不终止其他用户 Node 服务。

在 Vite 开发服务器运行时同时执行生产 build 曾触发 Windows `EBUSY watch web/dist/vite.svg` 导致开发服务器退出。重启后完成剩余浏览器验收，最终停止开发服务器再 build 通过。此机器上建议按“先 build，再启动 dev；再 build 前先停 dev”顺序复现。本轮没有修改 watcher 或升级依赖。

组件测试采用 jsdom 和模拟 API；独立浏览器验收使用真实服务，两者分别计数。生产资源虽已构建，但浏览器验证对象是 Vite dev，未验证独立静态托管后的部署。未执行真实模型/Embedding/Tavily、向量索引成功、带来源问答或模型生成消息历史；这些不在用户本轮选择的范围。

已知剩余边界：文档版本与索引版本仍未建立持久一致性状态；索引失败可能已产生部分后端写入，前端只能标明不确定；并发摘要/关键词请求的顺序保护未扩展；协作多用户冲突处理未验证。工作台既有“AI已就绪”标记只取决于文档有 summary，不代表模型可用或索引完成；编辑器保留的文档摘要可能来自本地截取而非生成模型，展示口径仍应据此解释。后续按主验收决定是否单独清理旧标记，未扩大本次功能范围。
