# Linux服务流程基线审核

审核者：codex-root。来源为实际GitHub运行的安全产物，不是本地执行，不伪造本地receipt，也不调用只允许本地报告的accept入口。

- 提交：808768e5d20f837d3710b5368392b489e42a1853，工作区clean。
- GitHub运行：44128653867；job：134199331595；所有步骤success，包括启动、四流程、报告验证与清理。
- 产物：14349494055，service-flows-44128653867-1。
- ZIP SHA-256：e8edce7db1656b4b2d6ba8c2262635895250b514a36e33fcae85d02b6be0ba0d，与GitHub API digest一致。
- ZIP仅含report.json；严格schema校验通过；报告commit与运行commit一致；contract源码摘要与当前runner一致，runnerOs=Linux。
- 业务runId：20261117T170126Z-ec1d4463；4/4、26个检查通过。真实模型禁用，Embedding明确为测试替身，费用null。

在上述来源和内容独立核对后，按现有不可变基线格式新建service-flows-linux-20261120.json（独占创建），没有覆盖Windows或旧组件基线。acceptance.reportDigest是内容完整性校验，不是签名或对不可信生产者的证明。本文件保留人工审核来源，不能把任意上传JSON当成可信基线。

CI新增该Linux基线比较门；后续新候选实际执行及比较尚待运行，不能用基线自比较替代独立候选结果。此前四流程硬门已在远端通过。
