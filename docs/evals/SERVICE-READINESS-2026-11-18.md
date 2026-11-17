# Linux服务就绪探针修正

远端69b60bf：组件运行43839511242的前后端job均成功；服务运行43839511179在启动就绪步骤失败，初始化、构建和清理成功，四条HTTP流程未运行。公共日志仅有RUNTIME_FAILED_UNCLASSIFIED，没有原始私有服务日志或artifact，不能宣称已从远端日志确定唯一原因。

只读本地复现确认探针契约错误：GET /api/users/login返回HTTP200、业务500，不是旧脚本等待的405；AI /v3/api-docs也返回HTTP200、业务500，因此旧脚本仅检查状态200会误判AI就绪。该观察不改变业务异常处理或权限。

最小修正：gateway改用已有GET /api/users/lookup查询随机生成的不存在用户名，严格检查四字段Result、code606、data=null；该只读请求验证gateway/user/DB链路，不创建用户或令牌。AI启动和重启改用已有工具目录接口，严格检查code200和非空、字段类型合法的工具数组，不固定工具名单。其他三服务继续使用OpenAPI，但增加版本/info/paths结构检查，拒绝任意200错误或HTML。

安全诊断只输出白名单stage、service、reason和最后HTTP状态，不输出URL、响应正文、异常消息或凭据。就绪超时与进程身份变化具有独立原因码，保留原有依赖错误分类。

验证：11/11 helper测试通过，覆盖旧200错误拒绝、正确lookup接收、工具目录类型校验、白名单诊断与原有进程/凭据保护。本地五个实际服务通过新探针验证（只读HTTP，不重启共享服务）。Linux进程启动/重启及远端四流程需集成后重新运行；本记录不是远端修复通过结论。

命令：`python -m unittest discover -s scripts/service-evals-runtime -p test_linux_runtime.py`；另直接调用同一wait_http实现对本地18081/18082/18084 OpenAPI、18083工具目录和18080随机lookup完成五项校验。未修改业务源码、权限、模型配置或评测断言。
