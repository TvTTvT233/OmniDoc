# MCP 搜索接入：自动测试报告

## T00 环境

- Java：Eclipse Adoptium 17.0.19
- Maven Wrapper：3.9.12
- OS：Windows 10 x64
- 分支：`docs/bootstrap-project-context`
- 起始 HEAD：`8427f59`
- 测试对象：包含进入 T00 前的工作树修改；这些修改随后保存为 checkpoint `51caddf`

## 基线测试 1：无补充环境变量

命令：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am test
```

结果：失败。

- Tests run：53
- Failures：0
- Errors：1
- 通过：52
- 失败测试：`AllModelsTest.testAllModels`
- 原因：Spring 上下文无法解析 `${DASHSCOPE_API_KEY}`

结论：默认测试命令依赖未提供的模型环境变量，当前不能作为开箱即用的绿色基线。

## 基线测试 2：测试进程占位环境变量

仅在该测试进程设置非真实占位值后执行相同命令。

结果：通过。

- Tests run：53
- Failures：0
- Errors：0
- BUILD SUCCESS

限制：

- `AllModelsTest` 实际尝试调用了四个远程模型；
- 占位 Key 下四个调用均认证失败，实际模型成功数为 0/4；
- 测试捕获异常且没有断言远程模型必须成功，因此 53/53 不能证明任何模型真实可用。

## 基线测试 3：MCP 改造直接相关测试

命令：

```powershell
.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am "-Dtest=AgentExecutionServiceTest,AgentControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：通过。

- Tests run：32
- Failures：0
- Errors：0
- BUILD SUCCESS

该结果是 T01-T04 的主要回归基线。

## T00 其他检查

- `git diff --cached --name-status`：空；
- `git diff --check`：未发现空白错误，只出现既有 LF/CRLF 转换警告；
- T00 契约与直接回归测试：`AgentExecutionServiceTest`、`AgentControllerTest`、`McpContractTest` 共 35/35 通过，BUILD SUCCESS；
- 端到端服务启动：未执行；
- 真实 Tavily MCP：未执行；
- 网络、认证、超时和引用链路：待 T01-T04 实现后验证。

## T00 最终复验

- 日期：2026-08-09；
- 命令：`.\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -am "-Dtest=AgentExecutionServiceTest,AgentControllerTest,McpContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`；
- 结果：35/35 通过，Failures 0，Errors 0，Skipped 0，BUILD SUCCESS；
- 范围：MCP 供应商无关契约、现有 Agent 执行服务和 Agent Controller 回归；
- 限制：未连接真实 Tavily MCP，不能据此判断网络、认证或搜索结果质量。

## 后续测试矩阵

| 场景 | 自动测试 | 真实远程 | 状态 |
| --- | --- | --- | --- |
| MCP 关闭兼容 | 必须 | 不需要 | TODO |
| 初始化成功 | 模拟 Server | 可选 | TODO |
| 工具发现/白名单 | 模拟 Server | 可选 | TODO |
| Schema 映射 | 单元测试 | 不需要 | TODO |
| Search 调用成功 | 模拟 Server | 必须人工冒烟一次 | TODO |
| 认证失败 | 模拟 401 | 可选 | TODO |
| 超时/断网 | 模拟 | 可选 | TODO |
| 内部 RAG 路由 | 单元/集成 | 不需要 | TODO |
| 外部搜索路由 | 单元/集成 | 人工确认 | TODO |
| 来源引用 | 单元/集成 | 人工确认 | TODO |
| 密钥泄漏 | 静态/日志检查 | 人工复核 | TODO |
