package com.javaee.documentservice.controller;

import com.javaee.common.model.Result;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.service.DocumentAccessService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** JWT-only permission projection: never accepts gateway user headers or returns document content. */
@RestController
@RequestMapping("/api/documents")
public class DocumentReadAccessController {
    private final DocumentMapper documents;
    private final DocumentAccessService access;

    public DocumentReadAccessController(DocumentMapper documents, DocumentAccessService access) {
        this.documents = documents;
        this.access = access;
    }

    @GetMapping("/{id}/read-access")
    public ResponseEntity<Result<Map<String, String>>> readAccess(@PathVariable String id,
                                                                 Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return failure(401, "请先登录");
        }
        final Long userId;
        try {
            userId = Long.valueOf(authentication.getName());
        } catch (NumberFormatException exception) {
            return failure(401, "请先登录");
        }
        try {
            Document document = documents.selectById(id);
            if (document == null || !"active".equals(document.getStatus()) || !access.canRead(document, userId)) {
                return failure(404, "文档不存在或无权访问");
            }
            return ResponseEntity.ok(Result.success(Map.of("documentId", id, "grantedUserId", userId.toString())));
        } catch (RuntimeException exception) {
            return failure(503, "文档权限服务暂不可用");
        }
    }

    private ResponseEntity<Result<Map<String, String>>> failure(int status, String message) {
        return ResponseEntity.status(status).body(Result.fail(status, message));
    }
}
