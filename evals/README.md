# 固定任务评测 V1

本阶段实现固定任务集和版本比较，不包含 CI 定时触发、真实模型质量评分或实际 token/费用预算。30 个任务场景复用已有生产组件测试：文档/引用、Review M0、Agent 各 10 个。场景清单在 `suites/*.json`，每项写明输入、预期业务结果、禁止行为、测试入口和外部替身。

## 证据边界

第一版是确定性业务组件评测：真实生产服务/引擎/账本配合 H2、合成材料和模拟外部依赖。输入和断言由清单指向的 JUnit 方法执行，清单文字本身不生成测试。它不是上传真实材料后从 HTTP 到模型的端到端评测，也不评估模型对自然语言任务的理解。

关键场景包括保存后旧索引失效、历史引用绑定原版本、跨用户拒绝、手工规则发布、缺材料及双侧证据、IGNORE 不变 Ready、正式报告、审批目标变更、写结果未知停止重试、丢失通知补偿和恢复时复用结果。Reviewer 的合格材料 Ready 场景不是“首次缺件→连续补件→再审”的完整 HTTP 链。

任务评价依据业务断言。例如拒绝错误审批、返回 OUTCOME_UNKNOWN 或正确报告缺材料，都可计为评测成功；不把 API 的 success 字段作为统一评分标准。

## 使用

需要 Python 3.10+（仅标准库）、JDK 17 和项目 Maven wrapper。先在项目根目录设置 `JAVA_HOME`，不要与其他 Maven 执行器并发运行。

```powershell
python scripts/evals/evaluate.py validate
python -m unittest discover -s scripts/evals -p 'test_*.py' -v
python scripts/evals/evaluate.py run --repeats 1
```

执行器按模块调用实际 Maven 测试入口，显式启用 `task-evaluation` profile，将每轮 XML 写入独立的 `.local/evals/<runId>/xml/<trial>/<module>/`，不读取或删除普通 `target/surefire-reports` 的旧报告。不清生产库、不改运行服务，不使用本机真实模型凭据。每个场景的数据库/替身由原测试夹具重新建立。

结果打印到终端，文件写入忽略目录 `.local/evals/<runId>/`：

- `report.json`、`report.md`：30 个场景逐次结果、失败分类、通过率、硬门槛和代码/任务集/评分器指纹。
- `*.private.log`、`xml/`：本机排障证据，可能包含 JVM 属性或测试日志，不能公开上传。
- `comparison.json`：指定基线后产生的逐场景比较。

报告中的 `productionRunId` 为 null：当前通过测试适配器执行，内部合成任务可能有自己的 ID，但没有对外提取统一业务 runId，不伪造追踪关系。模型版本表示禁用/测试替身，token/费用为 null 而非 0。耗时为测试方法耗时，不能作为线上延迟或模型速度。

## 基线审核与比较

当前可用基线为 `baselines/deterministic-v1-normalized.json`，采用忽略文本LF/CRLF差异的源码摘要；原 `deterministic-v1.json` 保留为修订前记录，与当前评分器不直接比较。基线由codex-root进行自动验收审阅，不代表用户已完成人工业务验收。

首次跑出通过结果后，应核对测试替身范围、每项断言和失败证据，再显式接受：

```powershell
python scripts/evals/evaluate.py accept --report .local/evals/RUN_ID/report.json --name reviewed-v2 --reviewed-by REVIEWER --reason "已核对30项断言及证据边界"
python scripts/evals/evaluate.py run --repeats 2 --baseline evals/baselines/deterministic-v1-normalized.json
```

上面的 `RUN_ID`、`REVIEWER` 需替换。审核者是实际审阅者，不应填写未参与的人。accept 仅接受本工具本机运行目录中的完整报告，核对当前30项定义、任务集/评分器指纹、原始 XML 摘要及逐项成绩，并拒绝额外未知字段。只允许全通过的结果，既有名称通过独占创建防止并发覆盖；更新基线需要新名称、新理由和独立审阅。执行 run 不会自动更新基线。接受后的内容摘要用于检测意外修改，不是数字签名，也不能防止有本机写权限的人同时伪造报告与原始证据。

比较已有结果：

```powershell
python scripts/evals/evaluate.py compare --baseline evals/baselines/deterministic-v1-normalized.json --candidate .local/evals/RUN_ID/report.json --output .local/evals/comparison.json
```

退出码：0 通过，1 已执行但失败/退化，2 配置、可比性或报告结构不满足。比较入口要求基线存在审阅记录且摘要一致，禁止输出路径覆盖输入或基线目录。跳过、缺少结果、重复结果、任何重复试验失败均不会被算作成功。Maven 整体失败或执行期间工作树发生变化时，即使选中测试看起来通过，也不能获得绿色结果。

任务集、测试夹具/辅助文件、评分器及执行配置不同的两轮不直接比较，返回 BLOCKED，需重新审阅基线；代码提交可以不同，正是比较对象。基线通过率不被平均值掩盖：关键场景任何失败阻断，非关键场景退化同样阻断。耗时只报告差值，第一版不设噪声较大的性能阈值。

## 维护

- 新增/调整场景时同步更新清单版本和实际断言。当前固定三组各 10 项，数量变化要显式修改加载约束并审阅，不允许静默漏跑。
- 每个适配器只对应一个非参数化 JUnit 方法，重复引用同一方法不能当作两个独立场景。
- 基线文件只有合成场景说明与经过白名单提取的结果，不保存原始请求、令牌或异常内容。发布前仍运行仓库敏感信息检查。
- XML 已隔离，但 Maven 仍使用模块共享编译产物；工具内有单实例锁，不能阻止外部直接启动 Maven，仍禁止并发构建。异常退出遗留锁时先确认没有评测/Maven 进程，再人工处理 `.local/evals/runner.lock`。`--timeout` 默认900秒，是每模块每次试验的进程超时，不是总运行费用预算。
- 后续可增加真实服务适配器和业务 runId 关联，再接真实模型、人工复核集、自动触发及成本预算；不要将本版通过率标为真实 Agent 或 RAG 效果准确率。
# 无密钥自动检查与评测中心

新增 `.github/workflows/deterministic-evals.yml`：PR、main/集成分支 push 和手动触发确定性评测，不设置定时任务，不调用外部模型。失败、缺项、跳过、不可比或敏感扫描失败都会使检查失败；只有公开白名单 `catalog.json` 可上传，原始日志/XML留在运行器本地。远端执行结果须以实际 Actions 记录为准。

本地查看已有结果：

```powershell
python scripts/evals/catalog.py export --output web/public/evals/catalog.json
```

启动现有 Web 后进入 `/evaluations`，或从工作台入口进入。页面提供运行列表、逐项试验、已接收基线与候选对比，也支持导入 CI 下载的 `catalog.json`。导入仅在浏览器内存中处理，不上传，不调用业务 API。该只读页面公开可访问；导出文件被 Git 忽略，默认不随仓库发布。若主动将它放入 Web 发布目录，须将其视为公开内容。

只导出某次运行（CI 使用该模式，防止混入历史报告）：

```powershell
python scripts/evals/catalog.py export --run-id <运行编号> --output .local/evaluation-center/catalog.json
```

导出器校验定义、元数据、测试 XML 摘要与结果，只投影固定字段，不复制异常文本、请求材料、路径、环境变量或基线审阅文字。默认最多读取最近50个运行目录；无效/不支持的历史报告显示排除数量。指定运行无效则命令失败；失败但证据一致的运行仍展示为 FAIL。已有已接收基线保持不变，不自动更新基线。

评测中心是本地/CI报告查看器，尚无服务端历史库、跨团队同步、定时评测或模型裁判。导入数据不是数字签名证据；请使用可信 CI 产物。耗时为组件测试参考，token/费用未测，不能推断真实模型质量。
