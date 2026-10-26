package com.javaee.documentservice.controller;

import com.javaee.common.model.Result;
import com.javaee.documentservice.consistency.DocumentIndexService;
import com.javaee.documentservice.security.RequestUserContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentIndexController {
    private final DocumentIndexService service;
    private final RequestUserContext user;
    public DocumentIndexController(DocumentIndexService service,RequestUserContext user){this.service=service;this.user=user;}
    @GetMapping("/{id}/index-state") public Result<Map<String,Object>> state(@PathVariable String id){return Result.success(service.state(id,user.getRequiredUserId()));}
    @PostMapping("/{id}/index") public ResponseEntity<?> index(@PathVariable String id,@RequestBody DocumentIndexService.IndexRequest request){return ResponseEntity.accepted().body(Result.success(service.request(id,user.getRequiredUserId(),request,false)));}
    @PostMapping("/{id}/index/retry") public ResponseEntity<?> retry(@PathVariable String id,@RequestBody DocumentIndexService.IndexRequest request){return ResponseEntity.accepted().body(Result.success(service.request(id,user.getRequiredUserId(),request,true)));}
    @GetMapping("/{id}/references/{referenceId}") public Result<Map<String,Object>> reference(@PathVariable String id,@PathVariable String referenceId){return Result.success(service.resolve(id,referenceId,user.getRequiredUserId()));}
}
