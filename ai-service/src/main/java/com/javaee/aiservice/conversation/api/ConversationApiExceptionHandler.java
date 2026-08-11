package com.javaee.aiservice.conversation.api;

import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import com.javaee.aiservice.conversation.runtime.RuntimeStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ConversationController.class)
public final class ConversationApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ConversationApiExceptionHandler.class);

    @ExceptionHandler(ConversationMemoryException.class)
    public ResponseEntity<ConversationApiErrorResponse> handleConversationError(
            ConversationMemoryException error
    ) {
        HttpStatus status = statusFor(error.getCode());
        String publicCode = error.getCode() == ConversationErrorCode.INVALID_CONVERSATION_REQUEST
                ? "CONVERSATION_REQUEST_INVALID"
                : error.getCode().name();
        return ResponseEntity.status(status)
                .body(new ConversationApiErrorResponse(publicCode, error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ConversationApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException error
    ) {
        return ResponseEntity.badRequest().body(new ConversationApiErrorResponse(
                "CONVERSATION_REQUEST_INVALID",
                "Conversation request body is invalid"));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ConversationApiErrorResponse> handleAuthentication(SecurityException error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ConversationApiErrorResponse(
                "AUTHENTICATION_REQUIRED",
                "Authentication is required"));
    }

    @ExceptionHandler(RuntimeStateException.class)
    public ResponseEntity<ConversationApiErrorResponse> handleRuntimeState(RuntimeStateException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ConversationApiErrorResponse(
                error.getCode(),
                "Conversation runtime cleanup is temporarily unavailable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ConversationApiErrorResponse> handleUnexpected(Exception error) {
        log.error("Unexpected structured conversation API failure", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ConversationApiErrorResponse(
                "CONVERSATION_REQUEST_FAILED",
                "Conversation request failed"));
    }

    private static HttpStatus statusFor(ConversationErrorCode code) {
        return switch (code) {
            case CONVERSATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONVERSATION_ARCHIVED, CONVERSATION_CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_CONVERSATION_CURSOR, INVALID_CONVERSATION_REQUEST -> HttpStatus.BAD_REQUEST;
            case MEMORY_PERSISTENCE_DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
