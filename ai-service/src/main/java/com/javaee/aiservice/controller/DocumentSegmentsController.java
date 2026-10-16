package com.javaee.aiservice.controller;

import com.javaee.aiservice.client.DocumentReadAccessClient;
import com.javaee.aiservice.rag.DocumentSegmentReadService;
import com.javaee.aiservice.rag.SegmentReadException;
import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.common.model.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/rag/document")
public class DocumentSegmentsController {
    private final RequestUserContext users;
    private final DocumentReadAccessClient access;
    private final DocumentSegmentReadService segments;

    public DocumentSegmentsController(RequestUserContext users, DocumentReadAccessClient access,
                                      DocumentSegmentReadService segments) {
        this.users = users;
        this.access = access;
        this.segments = segments;
    }

    @GetMapping("/{documentId}/segments")
    public ResponseEntity<Result<DocumentSegmentReadService.View>> read(@PathVariable String documentId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        try {
            String userId = users.getCurrentUserId().orElseThrow(() -> new SegmentReadException(401, "请先登录"));
            access.assertCanRead(documentId, userId, authorization);
            return ResponseEntity.ok(Result.success(segments.read(documentId, userId)));
        } catch (SegmentReadException exception) {
            return ResponseEntity.status(exception.status()).body(Result.fail(exception.status(), exception.getMessage()));
        }
    }
}
