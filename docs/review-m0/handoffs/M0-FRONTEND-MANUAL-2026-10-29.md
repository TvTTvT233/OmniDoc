# Review M0 手工清单前端接入验收

最终更新：2026-10-31。前端实现与本轮七阶段真实浏览器验收已完成；脚本实际于 10/30 20:50（Asia/Shanghai）结束，10/31 读取原会话退出码 0、复核 JSON/截图并完成临时进程清理，没有重复创建案例或运行已完成写操作。用户本人尚未进行人工接受，不标记 HUMAN_ACCEPTED。

范围：本轮用户确认的手工规则闭环；自动候选提取未接通，页面明确说明。仅材料齐全性和副本名称检查，不扩展 V1 的资格、正文、事实或印章审查。

## 实现

- 仪表盘“材料预审”入口、`/review-m0` 服务端分页列表、`/review-m0/:caseId` 案例页。
- LOCAL_ONLY 新案例；手工新增/修改规则、显式适用性和命名大小写选择、逐项确认发布不可变清单。
- 官方依据选择/上传、分类、移除引用；未处理依据阻止发布的原因及继续手工流程的操作说明。移除保留原件。
- 使用 owner-aware 文件上传及分页文件库（`/api/files/owned`），真实刷新后选择已有文件；材料逻辑名称、显式多对多用途分配、上传完毕确认；完整/单文件审查及任务轮询、取消和刷新恢复。
- 规则与材料两侧证据；报告使用自身的清单快照展示历史规则；过期提示、保留记录的 IGNORE、当前报告切换；副本下载校验 SHA-256 和服务端 UTF-8 名称。
- 写请求幂等键保留至显式成功，失败保留输入；并发提交、跨案例/同案例迟到响应、读取失败及旧报告不误报通过。
- 历史报告与不相关任务同时在 URL 时，任务完成不覆盖用户选中的旧报告；主动提交的新任务清除旧报告指针，执行中刷新仍追踪新任务。

## 真实联调修复

`CaseResponse.checklistRevision` 表示已发布进度，不能作为是否存在手工草稿的判断。前端始终读取草稿，只有草稿端点的精确 `404 REVIEW_M0_RESOURCE_NOT_FOUND` 返回空状态并避免误报服务器错误；401、503 和其他接口 404 保持真实失败。

复用旧上传入口会生成无 owner 的文件，无法通过 Review 附加时的权限检查。本轮改用 `/api/files/owned`；已有文件选择也只请求该 owner 列表，不猜测或迁移旧文件归属。首次失败遗留的合成文件 `dcaa6d14-181c-46a2-a559-85d33a3d7129` 已记录在本地 fixture，原件保留。新增列表由后端另行验证。

## 自动验证

工作目录 `D:\OmniDoc\web`，使用配置的 Node 运行 Vitest/Vite。

| 检查 | 结果 | 本地证据 |
| --- | --- | --- |
| `vitest run --config vitest.baseline-state.config.js` | 101/101，10 个文件通过 | `.local/review-m0-ui-evidence/frontend-state-release.log` |
| `vitest run --config vitest.startup.config.js`（独立进程） | 1/1 通过；后续未改启动入口 | `.local/review-m0-ui-evidence/frontend-startup.log` |
| `vite build` | 通过，37.65 秒；已有大块体积提示仍存在 | `.local/review-m0-ui-evidence/frontend-build-release.log` |
| owned 上传/列表、API、状态与页面专项 | 最终 39/39 通过（此前 101 项基线后，本次增加 5 项） | `.local/review-m0-ui-evidence/frontend-owned-final-r2.log` |
| owned 文件接入后的最终 `vite build` | 通过，48.68 秒；仅已有体积提示 | `.local/review-m0-ui-evidence/frontend-build-owned-final.log` |
| `git diff --check -- web` | 通过 | 命令输出 |

最初构建发现模板标签拼写并已修复；真实浏览器首先发现上述草稿读取问题。测试数字不代表真实浏览器全部完成。

## 浏览器验收记录（本轮完成）

使用真实 Edge、Vite、网关及实际服务；无 HTTP mock，无外部模型调用。固定合成 PDF，已有唯一案例保存在忽略目录 `browser-fixture.json`，失败后续跑不重复创建案例。登录凭据仅从本机私有文件读取，报告不包含凭据。

- setup 已通过：真实登录、新建案例、两条 USER_ADDED 规则逐项确认、发布后最新 draft 为 CONFIRMED 且仍保留两条规则；页面脚本错误 0。截图 `setup-confirmed-rules.png`。
- initial 已通过：真实 owner 上传、显式分配 Application、上传完毕确认、worker 执行报告缺失 1 / 命名 1、规则与材料两侧证据完整，页面脚本错误 0。截图 `initial-initial-report.png`。
- library 已通过：账号文件列表经 HTTP 恢复、选择已有 application 正确回填名称、刷新清除旧选择、整页刷新后再次从服务器选择；列表排除无 owner 的 legacy 合成残留，页面脚本错误 0。截图 `library-owned-file-selection.png`。
- rename 已通过：带 Origin 的真实 PATCH 修改逻辑名称成功；旧报告 OUTDATED；浏览器下载副本名为 `application.pdf`，SHA-256 与上传原字节一致；单文件复审仅消除命名问题，保留缺失；IGNORE 保留问题、仍非 Ready。`browser-rename.json` 的 5 个检查条目均通过，截图 `rename-outdated-report.png`、`rename-ignored-report.png`。
- ready 已通过：补充 Proof、显式分配并重新确认上传后，完整审查得到 M0_READY、零问题；整页刷新恢复相同持久化 job/report 和 URL，返回服务端案例列表时，该验收案例只出现一次，未重复创建。`browser-ready.json` 的 4 个检查条目均通过，截图 `ready-ready-report.png`。
- basis 已通过：真实上传官方依据、ATTACHED 未处理警告和发布阻断、修改分类、移除案例引用；规则随当前依据修订重新确认并发布新快照，完整审查再次 Ready。`browser-basis.json` 的 3 个检查条目均通过，截图 `basis-detached-basis.png`、`basis-revised-ready-report.png`。
- history 已通过：显式打开最初报告并带上最新已完成 job，不被最新报告覆盖；仍显示旧快照的 Application 而不是 Application revised、两项旧问题及旧材料名称；整页刷新保持旧报告指针和规则证据，再由用户明确点击切到最新 Ready 报告。`browser-history.json` 的 4 个检查条目均通过，截图 `history-historical-report.png`。

七阶段均 `passed=true`、`pageErrors=0`、`httpMock=false`、`externalModelCalls=0`。这些检查条目包含登录和多个底层断言，不与单元测试数量相加。当前案例始终为同一合成案例；3 个 owner-aware 上传文件与 1 个早期 legacy 失败上传、案例和审计报告保留，不硬删原件或审计记录。

本次续跑命令（仓库根目录，使用上述 Node）：`node .local/review-m0-ui-evidence/browser-review.cjs rename`，依次为 `ready`、`basis`、`history`，任何阶段非零即停止。执行会话 81421 最终退出 0；10/31 只读取结果，不重放这些写操作。视觉复核确认忽略、Ready、官方依据移除与历史双侧证据均可清楚辨认，无遮挡或内容截断。

自动验收脚本 `.local/review-m0-ui-evidence/browser-review.cjs` 的上传首次超时是脚本在文件库加载尚未结束时设置文件输入，已改为等待输入可用；该次未附加材料。运行状态以各阶段 JSON 为准。

owner picker 初次专项 38/39：失败来自测试在 unmount 后读取 Vue Test Utils 清空的事件记录，已改外部监听断言；产品行为未因该测试失败改动，复验 39/39。

10/30 先前包真实浏览器 rename 阶段发现带 Origin 的 PATCH 返回 403。common 与 gateway 的 CORS 允许方法遗漏 PATCH；Vite 正常代理带 Origin，先前无 Origin 的 HTTP 验收未覆盖此条件。后端补真实回归与配置修复、统一五服务包后，上述 rename/basis 的实际 PATCH 均通过；前端未移除 Origin 绕过检查。原失败 JSON `browser-rename-cors-failure.json` 和失败截图 `rename-failure.png` 保留。

## 变更范围与限制

本地人工验收入口：后端服务由 runtime 保留后，在 `D:\OmniDoc\web` 的 PowerShell 运行本轮已验证的启动命令 `& 'C:\Users\1\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort`，打开 `http://127.0.0.1:5173/review-m0`，登录后进入“材料预审”。

10/31 已核验本轮 Vite PID 75292 的可执行文件路径与启动时间后停止；5173 无监听，Playwright 临时 Edge 进程为 0，每阶段脚本均关闭自身浏览器。证据 `frontend-cleanup-20261031.json`；后端服务由 runtime 管理，本任务未停止它们。没有改动或终止无关浏览器/进程。

实现文件位于 `web/src/api/reviewM0*`、`web/src/composables/useReviewM0*`、`web/src/views/review/`、路由、仪表盘入口及全局请求中精确空草稿提示条件。本分报告以外的根文档由主协调更新。

自动提取仍未实现。幂等重试缓存仅在当前页面会话内，不能承诺刷新后对未确认写请求恢复相同幂等键。原始文件不改名、不覆盖；下载生成逻辑名称副本。没有提交或推送 Git。
