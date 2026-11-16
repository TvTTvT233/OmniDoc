# 服务流程持续检查入口（待远端验收）

新增独立 `service-flows.yml`，在PR、main/集成分支push或手动触发时，使用Linux干净机器构建、启动独立Docker依赖和五个实际服务，执行四条HTTP流程。只有Embedding使用明确的测试入口，Chat和MCP禁用。组件评测与服务评测保持独立。

`linux_runtime.py` 使用独立本地状态目录、随机凭据、子进程环境白名单、固定回环端口、真实HTTP就绪等待；仅持有PID、启动时间和可执行路径一致的进程可停止。重启前核对全部服务包、全部Embedding夹具类和模型禁用配置摘要，输出同包不同进程证明。日志、密码和进程清单保留在忽略目录，工作流只上传经过白名单校验的单个report.json。

本入口尚未在Linux实际完成运行，不能将源码/静态测试通过写为远端通过。Linux首次四流程全过并经审核后才能接收Linux服务基线并加入比较；本提交不伪造基线，不从Windows报告移植成功结果。当前workflow gate为固定四流程全部PASS，不宣称已有基线比较或性能退化门。

本地检查：`python -m unittest discover -s scripts/service-evals-runtime -p test_linux_runtime.py`，覆盖环境凭据/Java注入隔离、PID复用拒绝、包变化拒绝重启、越界proof拒绝、就绪前进程退出。该测试不替代实际Docker、Linux、HTTP或重启验收。

启动依赖共用root协调提供的compose.yml。Linux入口、restart包装及workflow须与该文件一起交付。脚本不自动删除已有数据库卷；GitHub托管机器在job结束后回收，运行时stop仅停止本项目依赖和已登记进程。
