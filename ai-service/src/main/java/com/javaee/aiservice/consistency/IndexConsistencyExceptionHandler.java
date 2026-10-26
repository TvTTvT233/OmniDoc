package com.javaee.aiservice.consistency;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class IndexConsistencyExceptionHandler {
    @ExceptionHandler(IndexConsistencyException.class)
    public ResponseEntity<Map<String,Object>> handle(IndexConsistencyException error) {
        int status = error.code().equals("LEGACY_INDEX_WRITE_DISABLED") || error.code().equals("INDEX_NOT_READY") ? 409 : 503;
        String message = error.code().equals("LEGACY_INDEX_WRITE_DISABLED")
                ? "请先保存文档，再通过文档索引入口更新索引"
                : error.code().equals("INDEX_NOT_READY") ? "当前文档版本尚无可用索引，请查看索引状态" : "索引依赖暂不可用，请查看索引状态后重试";
        return ResponseEntity.status(status).body(Map.of("errorCode", error.code(), "message", message));
    }
}
