package com.javaee.documentservice.controller;

import com.javaee.common.model.Result;
import com.javaee.documentservice.consistency.DocumentIndexService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Not routed by the public gateway; authentication is enforced by the dedicated filter. */
@RestController
@RequestMapping("/internal/index")
public class InternalDocumentIndexController {
    private final DocumentIndexService service;
    public InternalDocumentIndexController(DocumentIndexService service){this.service=service;}
    @PostMapping("/tasks/claim") public Result<Map<String,Object>> claim(@RequestBody DocumentIndexService.ClaimRequest r){return Result.success(service.claim(r));}
    @PostMapping("/tasks/{id}/renew") public Result<Map<String,Object>> renew(@PathVariable String id,@RequestBody DocumentIndexService.LeaseRequest r){return Result.success(service.renew(id,r));}
    @PostMapping("/tasks/{id}/fail") public Result<Map<String,Object>> fail(@PathVariable String id,@RequestBody DocumentIndexService.FailureRequest r){return Result.success(service.fail(id,r));}
    @PostMapping("/tasks/{id}/complete") public Result<Map<String,Object>> complete(@PathVariable String id,@RequestBody DocumentIndexService.CompleteRequest r){return Result.success(service.complete(id,r));}
    @PostMapping("/visibility") public Result<Map<String,Object>> visibility(@RequestBody DocumentIndexService.VisibilityRequest r){return Result.success(service.visibility(r));}
}
