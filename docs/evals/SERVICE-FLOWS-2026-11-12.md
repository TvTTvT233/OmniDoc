# 真实服务流程评测 V1

本阶段独立于原30项组件评测，mode为 `service-flows-v1`。使用真实loopback HTTP、独立数据和合成材料；不读取既有用户session，不发送外部模型请求。代码已实施，最终真实运行由主协调在隔离环境执行；本交接不宣称HTTP流程已通过。

## 运行契约

```powershell
python scripts/evals/service_flows.py --base-url http://127.0.0.1:18080 --isolated-runtime --restart-script scripts/service-evals-runtime/Restart-Ai.ps1 --artifact-sha256 <本轮AI包SHA256> --timeout 180
python scripts/evals/service_report.py validate --report .local/service-evals/<runId>/report.json
python scripts/evals/service_report.py accept --report .local/service-evals/<runId>/report.json --name service-v1
python scripts/evals/service_report.py compare --report .local/service-evals/<candidate>/report.json --baseline evals/service-baselines/service-v1.json
```

必须显式提供隔离环境声明。只允许loopback HTTP且禁止重定向，不允许将凭据转发到其他目的地。自动注册两个随机合成账号及唯一材料；保留数据供验收，不删除原有资料。模型目录必须全部disabled。执行失败/缺少前提不能变绿。

四条流程：文档创建/解析/索引/保存/旧generation不可用/历史引用原文/新版本/跨owner；Review手工规则发布/缺件/双侧证据/补件/旧报告过期/复审/跨owner；Agent确定性统计/重复同run/异载荷409/owner隔离/JDBC结果；审批WAITING_INPUT/真实不同PID同包重启/状态恢复/拒绝/迟到确认不写入。

仅Embedding测试替身入口：`com.javaee.aiservice.consistency.ServiceFlowEvaluationApplication`。只将该入口及nested class复制至独立test classpath，勿暴露全部test-classes。保留生产ChatService与禁用模型，避免旧SyntheticConsistencyApplication强制rag-answer计划改变Agent流程。模型名称 `synthetic-embedding-v1`，维度16；document配置 `omnidoc.index.embedding-model` 与 `omnidoc.index.embedding-dimension` 对应，AI使用既有生产worker与JDBC Agent。

重启脚本由受信任的本地操作者指定，不从报告读取命令；参数 `-ProofPath <绝对路径>`。必须核对原进程身份，只停止本轮AI，重启同包，写 `{beforePid,afterPid,artifactSha256}`。runner验证PID不同且包摘要匹配，并有界等待HTTP恢复。运行器不自行猜测PID或结束进程。脚本证明依赖受信本机运行，不是独立安全签名。

## 报告和界面

每次独立目录 `.local/service-evals/<runId>/`，公开 `report.json` 仅固定字段、计数、状态、步骤名和实际业务runId；token、账号、原始正文、URL、异常不进入报告。重启日志/证明及摘要receipt均private。文档版本流程无任务runId，明确NOT_APPLICABLE；其他成功流程必须含实际runId。记录commit及dirty状态，评分契约摘要随runner/验证器/Embedding夹具改变，配置不同拒绝比较。

基线显式accept，限定本地run目录、全通过、当前契约和receipt摘要一致，独占写入 `evals/service-baselines/`，不改原组件基线。摘要检测偶然篡改，不防本机攻击者重造证据；验收方仍须检查真实运行和重启证据。比较返回失败候选ID，失败退出1，无效/不可比退出2。

评测中心增加独立“真实服务流程评测”报告导入区，100KB上限、白名单字段、完整步骤及汇总校验；不上传、不混入组件通过率。服务基线比较目前通过CLI，页面用于逐项查看。未增加服务端历史库或定时任务。

## 当前验证

- Python新增11项自检通过：未知字段、缺步骤、伪绿、缺真实runId、基线摘要、非有限耗时、非loopback与重定向等。
- 前端专项28/28（原评测中心19+新增服务报告9）通过。
- `git diff --check`通过。
- Java测试入口编译、真实服务HTTP四条流程、真实同包重启及最终浏览器验收由root集成后执行，当前不记为通过。生产服务原实现未改，不调用真实模型。
