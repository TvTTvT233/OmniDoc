# 分段读取权限与状态：真实运行验证

执行日期：2026-10-16；恢复核对和收尾：2026-10-17，Asia/Shanghai。起点 `integration/core-baseline` / `05c2d639b0073ae8e1b7f8f481ef9ddcf74e2b4d`。运行的是该起点加本轮未提交修复构建的候选产物，不将起点提交本身视为已包含修复。本轮运行执行者只修改验收脚本和本文档；业务修复由后端/前端执行者完成。

## 约束与环境

- 复用此前五个应用和隔离 Compose 项目 `omnidoc-baseline-20261014`，未修改其他项目的容器或数据卷。
- AI 仍使用 `local` + `scripts/baseline/no-external-models.yml`；不调用外部模型或Embedding，不将手工数据当作索引成功。
- 凭据从本机Git忽略的测试会话文件读取，报告不包含密码/JWT；各次请求使用新登录令牌。
- 真实后端端口：gateway8080、user8081、file8082、AI8083、document8084；Redis为本机16379，应用库DB1。
- 原始证据目录：`.local/baseline-evidence/segment-access-2026-10-16/`。前端浏览器证据由前端执行者独立记录，API通过不替代页面通过。

## 修复前证据

原合成TXT文档 `90164414a872592b9201b209852d38ba` 的同一次owner访问：

| 检查 | 真实结果 |
| --- | --- |
| 重新登录 | 成功，JWT不输出 |
| GET `/api/documents/{id}` | HTTP200、business200，文档userId与登录用户一致 |
| Redis DB1 `doc:{id}` | EXISTS=0 |
| Redis DB1 `doc_segments:{id}` | EXISTS=0 |
| GET `/api/ai/rag/document/{id}/segments` | HTTP200、business403、无权访问该文档 |

证据：`before.json`。这确认了同一未索引文档在文档服务可访问，而缺少知识库元数据却被旧分段接口拒绝；不是仅凭源码推测，也没有通过删除权限判断来绕过。

## 合成分段读取夹具

`scripts/baseline/Test-SegmentAccessFixtures.ps1` 实际创建一个新的合成TXT/文档 `04e2d0cacd565da68aec353b56c8de97` 和一个不同用户。原文档和既有材料未修改。

本轮独立夹具直接写入以下Redis键，并设置24小时TTL：

- `doc:{新文档id}`：userId和knowledgeBaseId两个JSON hash值。
- `doc_segments:{新文档id}`：两个合法分段ID的JSON数组。
- `segment:{新文档id}_seg_0` / `_seg_1`：两段固定合成文本的JSON字符串。

没有调用索引API，没有请求Embedding，没有写向量。夹具只验证真实HTTP、文档权限和Redis分段读取，不证明检索或向量构建成功。旧服务下已单独确认HTTP200/business200、两段内容精确匹配，证明夹具序列化有效，记录 `fixture-before-sanity.json`。

清单位于 `fixtures.json`，不同用户凭据位于 `other-session.private.json`；后者仅本机使用，不提交或发布。脚本再次运行时拒绝重复创建；`-Cleanup`只删除清单内对应的5个候选键（实际创建4个键，content键仅列为有界清理目标），保留合成文件/文档用于继续复查，不对任意前缀执行广泛删除。

## 修复后验收命令与矩阵

使用PowerShell7，从仓库根目录执行：

```powershell
./scripts/baseline/Test-SegmentAccess.ps1
# 以下两项会短暂停止本轮依赖，必须与浏览器验收错开。
./scripts/baseline/Test-SegmentAccess.ps1 -Mode DocumentServiceUnavailable
./scripts/baseline/Test-SegmentAccess.ps1 -Mode RedisUnavailable
```

脚本只在自己的合成夹具上短暂删除分段/列表或改变索引归属，并在finally恢复。文档服务停止根据PID/启动时间校验身份；Redis停止前核对精确Compose项目标签。故障场景finally恢复依赖，不删除卷。

10月16日实际执行：正常/异常权限与数据状态矩阵 **14/14通过**，文档权限服务停机 **1/1通过**，Redis停机 **1/1通过**；三个脚本调用均退出0。10月17日仅恢复核对与浏览器收尾，未重复创建账号/文档或重跑全矩阵。

| 组别 | 验收内容 | 实际结果 |
| --- | --- | --- |
| owner未索引 | 200 + NOT_INDEXED + 空segments | 通过1项 |
| owner合法分段夹具 | 200 + SEGMENTS_AVAILABLE + 两段准确内容 | 通过1项 |
| 其他用户 | 未索引/分段文档均404且无正文 | 通过2项 |
| 不存在 | 404且无正文 | 通过1项 |
| 未登录 | 网关与AI直连均401；AI直连同时携带伪造user header | 通过2项；网关401正文未含business code |
| JWT专用read-access | 无JWT伪造header401、他人JWT伪造owner header404、owner200 | 通过3项 |
| 索引scope冲突 | 文档可读但索引归属不同403 | 通过1项 |
| 部分数据 | 缺分段内容/缺列表409；恢复后可读 | 通过3项 |
| 文档权限服务故障 | 503，不能变成NOT_INDEXED | 通过1项，127ms |
| Redis故障 | 503，不能变成NOT_INDEXED | 通过1项，21000ms |

Redis故障使用AI直连端口8083，隔离网关自身Redis依赖的影响。503分类正确，但本次耗时约21秒，属于已观察到的故障反馈延迟，不能据此宣称性能达标。其他矩阵请求经网关8080，明确标记的JWT窄接口/AI直连例外。

逐条原始结果分别是 `after-Normal.json`、`after-DocumentServiceUnavailable.json`、`after-RedisUnavailable.json`。恢复后 `after-recovery.json` 确认Redis PONG、owner未索引状态恢复、合成两段读取恢复、四个注册模型仍禁用。文档服务已重新启动，Redis恢复原容器与数据卷；未删除卷或触碰其他项目。

## 产物与收尾状态

部署前安全停止本轮AI/document进程，解除Windows运行中jar占用后由后端执行者完成打包，再启动两服务；没有使用打包中间态产物。`environment-after.json`记录实际部署SHA256：

- AI：`214E4AA559ACCC3396A57FEDFC5F50F55A8915BB62CAFEE0FC5F620D48A592C9`
- document：`8EEBC5659C5C4068842B2592CE01D6EEEC6B59659099C3BBC772D4AB3415819B`

10月17日恢复核对记录 `environment-2026-10-17.json`：五服务均监听127.0.0.1；gateway8080/PID24980、user8081/PID6804、file8082/PID38660、AI8083/PID38740、document8084/PID45236。隔离项目五容器均运行，MySQL/Redis/RabbitMQ健康检查通过，MinIO/Qdrant运行。合成四键TTL仍约17小时，因此未续期或重建。PID为此时快照，停止服务应使用已有 `Stop-Services.ps1` 的PID/启动时间校验，不照抄PID直接结束进程。

10月17日前端确认真实浏览器三条验收完成后，已执行以下有界清理，实际删除4个合成Redis键，5个候选键检查剩余0，记录在 `fixture-cleanup.json`：

```powershell
./scripts/baseline/Test-SegmentAccessFixtures.ps1 -Cleanup
```

清理未删除合成文档/文件/账号；清理后该合成文档不再具有本次手工分段快照。前端独立浏览器证据 `frontend-browser-20261017.json` 和 `browser-*-20261017.png` 记录三条通过：owner未索引提示、owner两段准确展示、另一用户404拒绝且不误显未索引。未点击索引、未发送模型请求。

最终快照 `environment-final-2026-10-17.json` 确认上述五应用/五容器仍运行，前端执行者已关闭浏览器并停止本轮Vite5173。验收脚本PowerShell语法解析和 `git diff --check` 通过。仅清理本轮合成Redis键；没有停止其他项目、删除数据卷、安装软件或修改全局配置。10月17日收尾证据时间存储为UTC，换算北京时间为10月17日。

## 证据边界

`SEGMENTS_AVAILABLE`只说明分段快照可读，不等于全部向量索引完成或与文档最新版一致。全文向量路径可能没有分段列表，409只表示缺少可验证分段，不据此断言向量索引失败。本轮不扩展自动重索引、文档版本一致性、跨用户协作索引共享、真实模型效果或Review M0。
