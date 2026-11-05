package com.javaee.aiservice.taskrun.recovery;

import com.javaee.aiservice.controller.AgentController;
import com.javaee.aiservice.controller.AsyncAIController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataAccessException;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={AgentController.class,AsyncAIController.class})
public class AgentRecoveryExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> domain(ResponseStatusException error) {
        String code=error.getReason();
        if(code==null || !code.matches("[A-Z_]+")) code="AGENT_REQUEST_REJECTED";
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("errorCode",code,"message","任务状态已变化或请求不匹配，请刷新后核对。"));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storage(DataAccessException error) {
        return ResponseEntity.status(503).body(Map.of("errorCode","AGENT_STORAGE_UNAVAILABLE","message","任务记录暂不可用，已停止执行，请稍后重试。"));
    }
}
