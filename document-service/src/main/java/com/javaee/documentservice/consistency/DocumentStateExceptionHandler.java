package com.javaee.documentservice.consistency;

import com.javaee.common.exception.BusinessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.javaee.documentservice.controller")
public class DocumentStateExceptionHandler {
    @ExceptionHandler(DocumentStateException.class)
    public ResponseEntity<?> state(DocumentStateException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("errorCode", e.getErrorCode(), "message", e.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable(DataAccessException e) {
        return state(new DocumentStateException(503, "DOCUMENT_STORE_UNAVAILABLE", "文档存储暂不可用"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidBody(HttpMessageNotReadableException e) {
        return state(new DocumentStateException(400, "INVALID_REQUEST", "请求格式无效"));
    }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException e) {
        return state(new DocumentStateException(400, "DOCUMENT_REQUEST_REJECTED", "文档请求未完成"));
    }
}
