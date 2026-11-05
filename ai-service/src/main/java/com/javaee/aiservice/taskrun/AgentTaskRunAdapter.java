package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static com.javaee.aiservice.taskrun.TaskRunMetadata.*;

@Component
public class AgentTaskRunAdapter {
    private final AgentTaskRegistry registry;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.javaee.aiservice.taskrun.recovery.AgentRunStore durableStore;
    public AgentTaskRunAdapter(AgentTaskRegistry registry) { this.registry = registry; }

    public Optional<TaskRunView> findOwned(String userId, String traceId) {
        if(durableStore!=null) {
            var durable=durableStore.findOwned(userId,traceId);
            if(durable.isPresent()) {
                var run=durable.get();
                List<TaskRunView.Step> steps=new ArrayList<>();
                Object plan=run.checkpoint().get("plan");
                if(plan instanceof List<?> list) for(Object rawStep:list) {
                    Map<String,Object> step=map(rawStep);
                    steps.add(new TaskRunView.Step(text(step.get("id")),"TOOL",status(text(step.get("status"))),text(step.get("status")),integer(step.get("attempts")),null,null));
                }
                return Optional.of(new TaskRunView(1,"agent:"+traceId,"AGENT",traceId,"AGENT",run.status(),run.status(),
                        text(run.result().get("status")),run.reason(),"CANCELLED".equals(run.status()),run.attempt(),null,run.createdAt(),null,
                        Set.of("SUCCEEDED","FAILED").contains(run.status())?run.updatedAt():null,Instant.now(),
                        run.asyncJobId()==null?List.of():List.of(new TaskRunView.Link("TRIGGERED_BY","async:"+run.asyncJobId())),steps,List.of(),
                        run.result().isEmpty()?null:new TaskRunView.Result("AGENT_TRACE",traceId,text(run.result().get("status")),null),List.of(),
                        new TaskRunView.Capabilities("JDBC","CHECKPOINT_SUMMARY",true,List.of("UNCERTAIN_WRITES_REQUIRE_RECONCILIATION","NO_CROSS_SERVICE_EXACTLY_ONCE","SINGLE_STEP_RETRY_REQUIRES_NEW_REQUEST"))));
            }
            if(durableStore.known(traceId)) return Optional.empty();
        }
        Map<String, Object> snapshot = registry.getForProtocol(traceId);
        if (userId == null || !userId.equals(snapshot.get("userId"))) return Optional.empty();
        Map<String, Object> meta = map(snapshot.get(KEY));
        String raw = text(snapshot.get("status"));
        String state = status(raw);
        List<TaskRunView.ToolCall> calls = rows(meta.get("toolCalls")).stream().map(c ->
                new TaskRunView.ToolCall(text(c.get("toolCallId")), text(c.get("stepId")),
                        text(c.get("toolName")), status(text(c.get("status"))), integer(c.get("attempt")),
                        instant(c.get("startedAt")), instant(c.get("finishedAt")))).toList();
        Map<String, TaskRunView.Step> steps = new LinkedHashMap<>();
        for (TaskRunView.ToolCall call : calls) {
            TaskRunView.Step prior = steps.get(call.stepId());
            steps.put(call.stepId(), new TaskRunView.Step(call.stepId(), "TOOL", call.status(), null,
                    call.attempt(), prior == null ? call.startedAt() : prior.startedAt(), call.finishedAt()));
        }
        List<String> limitations = new ArrayList<>(List.of("BEST_EFFORT_REDIS_7D_LOCAL_CACHE",
                "NO_CRASH_RECOVERY", "NO_CROSS_INSTANCE_COORDINATION", "ONLY_OBSERVED_TOOL_STEPS",
                "SINGLE_STEP_RETRY_DOES_NOT_RECOMPUTE_ANSWER"));
        if (meta.isEmpty()) limitations.add("LEGACY_HISTORY_UNAVAILABLE");
        if (Boolean.TRUE.equals(meta.get("historyTruncated"))) limitations.add("HISTORY_TRUNCATED");
        String jobId = text(meta.get("asyncJobId"));
        return Optional.of(new TaskRunView(1, "agent:" + traceId, "AGENT", traceId, "AGENT",
                state, raw, raw, "WAITING_INPUT".equals(state)
                        ? "planned".equals(raw) ? "PLAN_CONFIRMATION" : "USER_ACTION" : null,
                Boolean.TRUE.equals(meta.get("cancellationRequested")), integer(meta.get("attempt")),
                number(meta.get("revision")), instant(meta.get("createdAt")), instant(meta.get("startedAt")),
                "WAITING_INPUT".equals(state) ? null : instant(meta.get("finishedAt")), Instant.now(),
                jobId == null ? List.of() : List.of(new TaskRunView.Link("TRIGGERED_BY", "async:" + jobId)),
                new ArrayList<>(steps.values()), calls,
                Set.of("SUCCEEDED", "FAILED", "CANCELLED", "WAITING_INPUT").contains(state)
                        ? new TaskRunView.Result("AGENT_TRACE", traceId, raw, null) : null,
                events(meta), new TaskRunView.Capabilities("BEST_EFFORT_REDIS_WITH_LOCAL_FALLBACK",
                        meta.isEmpty() ? "SNAPSHOT" : "BOUNDED_EVENTS", false, limitations)));
    }
}
