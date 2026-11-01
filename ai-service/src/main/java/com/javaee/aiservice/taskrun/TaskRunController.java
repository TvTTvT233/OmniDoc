package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.security.RequestUserContext;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/ai/task-runs")
public class TaskRunController {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final RequestUserContext users;
    private final AgentTaskRunAdapter agents;
    private final AsyncTaskRunAdapter async;
    private final ReviewTaskRunAdapter reviews;
    public TaskRunController(RequestUserContext users, AgentTaskRunAdapter agents,
            AsyncTaskRunAdapter async, ReviewTaskRunAdapter reviews) {
        this.users = users; this.agents = agents; this.async = async; this.reviews = reviews;
    }
    @GetMapping("/{runId}")
    public ResponseEntity<?> get(@PathVariable String runId, @RequestParam(required = false) String caseId) {
        Optional<String> owner = users.getCurrentUserId();
        if (owner.isEmpty()) return error(401, "AUTHENTICATION_REQUIRED", "请先登录");
        String[] parts = runId.split(":", -1);
        if (parts.length != 2 || !ID.matcher(parts[1]).matches())
            return error(400, "INVALID_TASK_RUN_ID", "任务标识格式无效");
        if ("review".equals(parts[0]) && (caseId == null || !ID.matcher(caseId).matches()))
            return error(400, "INVALID_TASK_RUN_CASE", "请提供有效的案例标识");
        try {
            Optional<TaskRunView> view = switch (parts[0]) {
                case "agent" -> agents.findOwned(owner.get(), parts[1]);
                case "async" -> async.findOwned(owner.get(), parts[1]);
                case "review" -> reviews.findOwned(owner.get(), caseId, parts[1]);
                default -> Optional.empty();
            };
            return view.<ResponseEntity<?>>map(v -> ResponseEntity.ok().header("Cache-Control", "no-store").body(v))
                    .orElseGet(() -> error(404, "TASK_RUN_NOT_FOUND", "任务不存在或不可访问"));
        } catch (DataAccessException error) {
            return error(503, "TASK_RUN_STORAGE_UNAVAILABLE", "任务状态暂不可用，请稍后重试");
        } catch (RuntimeException error) {
            return error(500, "TASK_RUN_READ_FAILED", "读取任务状态失败");
        }
    }
    private static ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("errorCode", code, "message", message));
    }
}
