package com.javaee.aiservice.controller;

import com.javaee.aiservice.memory.context.MemoryContextException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable HTTP boundary for Conversation Memory input-budget failures. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AgentController.class)
public final class AgentMemoryExceptionHandler {

    @ExceptionHandler(MemoryContextException.class)
    public ResponseEntity<AgentMemoryErrorResponse> handleContextTooLarge(MemoryContextException error) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new AgentMemoryErrorResponse(
                error.getCode(),
                "Conversation input exceeds the configured model context budget"));
    }

    public record AgentMemoryErrorResponse(String errorCode, String message) {
    }
}
