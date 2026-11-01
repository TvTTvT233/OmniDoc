# 统一任务运行协议 V1（2026-11-01）

## 目标与边界

把 Agent、异步 AI 请求和 Review 的现有执行事实映射为可查询的同一协议，提供真实状态、步骤/工具调用关联、结果引用和所有者隔离。复用原执行器和存储，不另建工作流引擎。旧接口保持兼容。

V1 不宣称具备统一崩溃恢复、跨实例恰好一次副作用、完整持久事件日志或真实模型质量评测。这些是后续阶段。历史数据缺失的信息为 null/空列表，并明确能力限制；不得倒造调用、时间或事件。

## 公共模型（冻结接口）

Java package `com.javaee.aiservice.taskrun`；`TaskRunView` 使用 record，嵌套 record 如下。所有时间为 ISO-8601 Instant，可空。所有数组非 null。枚举以字符串输出，未知原状态保留 sourceStatus，公共状态 UNKNOWN。

```
TaskRunView(int protocolVersion, String runId, String source, String sourceId,
 String kind, String status, String sourceStatus, String businessStatus,
 String waitingReason, boolean cancellationRequested, Integer attempt,
 Long revision, Instant createdAt, Instant startedAt, Instant finishedAt,
 Instant observedAt, List<Link> links, List<Step> steps,
 List<ToolCall> toolCalls, Result result, List<Event> events, Capabilities capabilities)
Link(String relation, String runId)
Step(String stepId, String kind, String status, String sourceStatus,
 Integer attempt, Instant startedAt, Instant finishedAt)
ToolCall(String toolCallId, String stepId, String toolName, String status,
 Integer attempt, Instant startedAt, Instant finishedAt)
Result(String kind, String resourceId, String outcome, String freshness)
Event(String eventId, String type, String stepId, String toolCallId,
 String status, Instant occurredAt)
Capabilities(String persistence, String history, boolean recoverySupported,
 List<String> limitations)
```

- `runId`：`agent:<traceId>` / `async:<jobId>` / `review:<jobId>`。同一 trace 的续接仍同 run；每次执行/工具尝试使用不碰撞的标识。不同来源即使 UUID 相同也不能碰撞。
- `source`：AGENT / ASYNC_AI / REVIEW。保留 sourceId、sourceStatus。关联使用 links，不把 Rabbit 请求和 Agent trace 伪装成相同 ID。
- 公共 `status`：QUEUED / RUNNING / WAITING_INPUT / SUCCEEDED / FAILED / CANCELLED / UNKNOWN。dry-run planned 为 WAITING_INPUT + waitingReason=PLAN_CONFIRMATION；action_required 为 WAITING_INPUT。取消请求不等于取消完成，单独 cancellationRequested。
- 执行状态与业务结论分离：Review SUCCEEDED + M0_NOT_READY 是正常完成的审查；OUTDATED 只代表结果新鲜度，不修改历史执行状态。Agent error 为 FAILED，action_required 不是成功。异步外层 success 保留在 sourceStatus，公共状态反映其 Agent 结果；外层 failed 优先 FAILED。
- `attempt` 不猜测，源无记录为 null；不把 Agent 循环次数称作重试次数。步骤 ID 必须在 run 内唯一；工具调用关联真实步骤，重试有独立调用 ID。
- `Result` 仅引用真实 trace、report、checklist 等既有资源；Review 不用最新报告替换本次 job 的历史报告。Review checkpoints 是 SUMMARY 类型步骤，未知开始时间为 null，工具调用为空；不能据 progressTotal 补足假步骤。
- `events` 只承载实际记录的白名单元数据，非完整事件源。旧记录缺失为 []；快照/检查点不能伪装成历史事件。history 说明 NONE / SNAPSHOT / BOUNDED_EVENTS 等能力。
- 新接口禁止返回原始任务文本、context、prompt、工具参数/结果正文、异常正文、审批令牌、JWT、存储地址或任意源 Map。错误仅稳定错误码与固定文案。

## 查询 API

`GET /api/ai/task-runs/{runId}` 返回裸 TaskRunView；Review 查询附 `?caseId=...` 作为资源定位，必须验证 case 和 job 的 owner/关联。身份只取 RequestUserContext；所有来源均无 admin owner 绕过。未知、异主、已删除统一 HTTP404 + `{errorCode: TASK_RUN_NOT_FOUND, message: ...}`；缺认证401、格式错误400、依赖不可用503、意外错误500，错误不透传异常。

公共适配器：`AgentTaskRunAdapter.findOwned(String userId, String traceId)`、`AsyncTaskRunAdapter.findOwned(String userId, String jobId)`、`ReviewTaskRunAdapter.findOwned(String userId, String caseId, String jobId)`，不存在/异主返回 Optional.empty；依赖错误不能吞成不存在。旧源存在本地降级时按 capabilities 如实声明。

新增 Agent 最小生命周期记录接入实际执行入口/结束/工具尝试，存于现有 AgentTaskRegistry 快照，避免只在最终完成时可查询。若必须补字段，使用内部白名单结构；保留 continuation 依赖的旧 snapshot 字段。异步与 Agent 的关联只由服务器写入，客户端不能注入。不复用现有全局 WebSocket 广播作为新协议的鉴权通道。

## 实施与验证

1. 公共模型、Agent/Async 适配与真实生命周期关联；兼容旧返回和原存储。
2. Review owner-only 投影，复用既有 job/checkpoint/report 事实；不改冻结 Review 状态及任务引擎。
3. owner-only 查询控制器与前端公共状态展示；保留原业务操作接口。
4. 状态矩阵、同源/跨源 ID、真实执行 hook、等待/错误映射、越权/匿名/不存在、序列化脱敏、旧 Agent/Review/前端直接回归；不调用外部模型。服务运行验证与纯测试分别记录。

主要风险是误报成功、丢失旧字段、泄露源上下文、把快照当恢复日志。以契约和行为回归控制；本轮不更新 main 或推送。
