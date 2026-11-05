package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.async.AsyncAIJobService;
import com.javaee.aiservice.async.AsyncAIJobVO;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static com.javaee.aiservice.taskrun.TaskRunMetadata.*;

@Component
public class AsyncTaskRunAdapter {
    private final AsyncAIJobService jobs;
    public AsyncTaskRunAdapter(AsyncAIJobService jobs) { this.jobs = jobs; }
    public Optional<TaskRunView> findOwned(String userId, String jobId) {
        return jobs.findOwned(jobId, userId).map(this::project);
    }
    private TaskRunView project(AsyncAIJobVO job) {
        String raw = job.getStatus();
        String state = status(raw);
        Map<String, Object> result = map(job.getResult());
        boolean durable="JDBC".equals(result.get("runtimePersistence"));
        String traceId = job.getTraceId();
        if (traceId == null && "agent".equals(job.getType())) traceId = text(result.get("traceId"));
        String business = "agent".equals(job.getType()) ? text(result.get("status")) : null;
        if ("agent".equals(job.getType()) && "success".equals(raw)) state = status(business);
        return new TaskRunView(1, "async:" + job.getJobId(), "ASYNC_AI", job.getJobId(),
                job.getType(), state, raw, business,
                "WAITING_INPUT".equals(state) ? durable && result.get("recoveryReason")!=null ? text(result.get("recoveryReason")) : "planned".equals(business) ? "PLAN_CONFIRMATION" : "USER_ACTION" : null,
                false, null, null, instant(job.getSubmittedAt()), instant(job.getStartedAt()),
                "WAITING_INPUT".equals(state) ? null : instant(job.getFinishedAt()), Instant.now(),
                traceId == null ? List.of() : List.of(new TaskRunView.Link("EXECUTES", "agent:" + traceId)),
                List.of(), List.of(), "success".equals(raw)
                        ? new TaskRunView.Result(traceId == null ? "ASYNC_RESULT" : "AGENT_TRACE",
                            traceId == null ? job.getJobId() : traceId, business, null) : null,
                List.of(), new TaskRunView.Capabilities(durable?"JDBC":"REDIS_TTL", "SNAPSHOT", durable,
                        durable?List.of("AGENT_RUN_IS_AUTHORITY","UNCERTAIN_WRITES_REQUIRE_RECONCILIATION"):
                        List.of("DEFAULT_TTL_24H", "NO_CRASH_RECOVERY", "OUTER_SUCCESS_MEANS_HANDLER_RETURNED")));
    }
}
