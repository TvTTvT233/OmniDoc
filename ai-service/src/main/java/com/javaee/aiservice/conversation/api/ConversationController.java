package com.javaee.aiservice.conversation.api;

import com.javaee.aiservice.security.RequestUserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/conversations")
public final class ConversationController {

    private final ConversationApiService conversationApiService;
    private final RequestUserContext requestUserContext;

    public ConversationController(
            ConversationApiService conversationApiService,
            RequestUserContext requestUserContext
    ) {
        this.conversationApiService = conversationApiService;
        this.requestUserContext = requestUserContext;
    }

    @PostMapping
    public ResponseEntity<ConversationResponse> create(
            @RequestBody(required = false) CreateConversationRequest request
    ) {
        ConversationResponse response = conversationApiService.create(authenticatedOwner(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ConversationPageResponse list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit
    ) {
        return conversationApiService.list(authenticatedOwner(), status, cursor, limit);
    }

    @GetMapping("/{conversationId}")
    public ConversationResponse get(@PathVariable String conversationId) {
        return conversationApiService.get(conversationId, authenticatedOwner());
    }

    @GetMapping("/{conversationId}/messages")
    public ConversationMessagePageResponse listMessages(
            @PathVariable String conversationId,
            @RequestParam(required = false) String beforeSequence,
            @RequestParam(required = false) String limit
    ) {
        return conversationApiService.listMessages(
                conversationId, authenticatedOwner(), beforeSequence, limit);
    }

    @PostMapping("/{conversationId}/archive")
    public ConversationResponse archive(@PathVariable String conversationId) {
        return conversationApiService.archive(conversationId, authenticatedOwner());
    }

    @PostMapping("/{conversationId}/restore")
    public ConversationResponse restore(@PathVariable String conversationId) {
        return conversationApiService.restore(conversationId, authenticatedOwner());
    }

    @DeleteMapping("/{conversationId}")
    public DeleteConversationResponse delete(@PathVariable String conversationId) {
        return conversationApiService.delete(conversationId, authenticatedOwner());
    }

    private String authenticatedOwner() {
        return requestUserContext.getRequiredUserId();
    }
}
