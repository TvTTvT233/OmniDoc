package com.javaee.aiservice.conversation.api;

import com.javaee.aiservice.conversation.model.Conversation;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessagePage;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.ConversationPage;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.model.ConversationStatus;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import com.javaee.aiservice.conversation.service.ConversationService;
import com.javaee.aiservice.conversation.runtime.RuntimeStateException;
import com.javaee.aiservice.security.RequestUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationControllerTest {

    private static final String OWNER = "authenticated-owner";
    private static final Instant NOW = Instant.parse("2026-08-11T02:00:00Z");

    private MockMvc mockMvc;
    private ConversationService conversationService;
    private RequestUserContext requestUserContext;
    private ConversationCursorCodec cursorCodec;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        requestUserContext = mock(RequestUserContext.class);
        when(requestUserContext.getRequiredUserId()).thenReturn(OWNER);
        cursorCodec = new ConversationCursorCodec("test-only-cursor-key-with-at-least-32-bytes");
        ConversationApiService apiService = new ConversationApiService(
                conversationService,
                cursorCodec,
                new ConversationPublicMessageMapper());
        ConversationController controller = new ConversationController(apiService, requestUserContext);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ConversationApiExceptionHandler())
                .build();
    }

    @Test
    void createIgnoresForgedUserIdAndReturnsOnlyThePublicConversationDto() throws Exception {
        when(conversationService.createConversation(OWNER, "Review"))
                .thenReturn(conversation("conv-1", OWNER, "Review", ConversationStatus.ACTIVE));

        mockMvc.perform(post("/api/ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Review","userId":"forged-owner","admin":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.title").value("Review"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$").value(not(hasKey("userId"))))
                .andExpect(jsonPath("$").value(not(hasKey("admin"))));

        verify(conversationService).createConversation(OWNER, "Review");
    }

    @Test
    void listUsesAuthenticatedOwnerAndSignedCursorContract() throws Exception {
        com.javaee.aiservice.conversation.model.ConversationCursor next =
                new com.javaee.aiservice.conversation.model.ConversationCursor(NOW, "conv-1");
        when(conversationService.listConversations(OWNER, ConversationStatus.ACTIVE, null, 2))
                .thenReturn(new ConversationPage(
                        List.of(conversation("conv-2", OWNER, null, ConversationStatus.ACTIVE)),
                        next));

        String body = mockMvc.perform(get("/api/ai/conversations")
                        .param("status", "ACTIVE")
                        .param("limit", "2")
                        .param("userId", "forged-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].conversationId").value("conv-2"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();

        String encodedCursor = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("nextCursor").asText();
        org.assertj.core.api.Assertions.assertThat(cursorCodec.decode(encodedCursor)).isEqualTo(next);
        verify(conversationService).listConversations(OWNER, ConversationStatus.ACTIVE, null, 2);
    }

    @Test
    void tamperedOrMalformedCursorReturnsStable400Carrier() throws Exception {
        String valid = cursorCodec.encode(
                new com.javaee.aiservice.conversation.model.ConversationCursor(NOW, "conv-1"));
        String tampered = (valid.charAt(0) == 'A' ? "B" : "A") + valid.substring(1);

        mockMvc.perform(get("/api/ai/conversations").param("cursor", tampered))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CONVERSATION_CURSOR"))
                .andExpect(jsonPath("$.message").isString());

        verify(conversationService, never()).listConversations(any(), any(), any(), any(Integer.class));
    }

    @Test
    void missingDeletedAndCrossOwnerRemainIndistinguishable() throws Exception {
        when(conversationService.requireOwned("conv-private", OWNER)).thenThrow(
                new ConversationMemoryException(
                        ConversationErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation was not found"));

        mockMvc.perform(get("/api/ai/conversations/conv-private")
                        .param("userId", "actual-owner-of-private-conversation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CONVERSATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Conversation was not found"));

        verify(conversationService).requireOwned("conv-private", OWNER);
    }

    @Test
    void messagePageIsStrictlyPublicAndToolEventsAreRedacted() throws Exception {
        List<ConversationMessage> stored = List.of(
                message(1, ConversationRole.USER, ConversationMessageType.TEXT,
                        "user text", null, Map.of("internal", "must-not-leak")),
                message(2, ConversationRole.SYSTEM, ConversationMessageType.TEXT,
                        "system prompt secret", null, Map.of()),
                message(3, ConversationRole.ASSISTANT, ConversationMessageType.TEXT,
                        "assistant text", "trace-3", Map.of("internal", "must-not-leak")),
                message(4, ConversationRole.TOOL, ConversationMessageType.TOOL_CALL,
                        "tool call arguments secret", "trace-4", Map.of("displayable", true)),
                message(5, ConversationRole.TOOL, ConversationMessageType.TOOL_RESULT,
                        "complete tool result secret", "trace-5", Map.of("displayable", true)),
                message(6, ConversationRole.TOOL, ConversationMessageType.EVENT,
                        "hidden event payload", "trace-6", Map.of("displayable", false)),
                message(7, ConversationRole.SYSTEM, ConversationMessageType.EVENT,
                        "legacy internal event", "trace-7", Map.of("displayable", true)),
                message(8, ConversationRole.TOOL, ConversationMessageType.EVENT,
                        "raw event payload containing approval-token-secret", "trace-8",
                        Map.of(
                                "displayable", true,
                                "eventType", "TOOL_FINISHED",
                                "label", "Search completed",
                                "status", "SUCCESS",
                                "toolName", "search",
                                "approvalToken", "must-not-leak")));
        when(conversationService.listMessages("conv-1", OWNER, 99L, 20))
                .thenReturn(new ConversationMessagePage(stored, 1L));

        mockMvc.perform(get("/api/ai/conversations/conv-1/messages")
                        .param("beforeSequence", "99")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].role").value("USER"))
                .andExpect(jsonPath("$.items[0].messageType").value("TEXT"))
                .andExpect(jsonPath("$.items[0].metadata").isEmpty())
                .andExpect(jsonPath("$.items[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.items[2].role").value("TOOL"))
                .andExpect(jsonPath("$.items[2].messageType").value("EVENT"))
                .andExpect(jsonPath("$.items[2].content").value("Search completed"))
                .andExpect(jsonPath("$.items[2].metadata.displayable").value(true))
                .andExpect(jsonPath("$.items[2].metadata.eventType").value("TOOL_FINISHED"))
                .andExpect(jsonPath("$.items[2].metadata.approvalToken").doesNotExist())
                .andExpect(jsonPath("$.nextBeforeSequence").value(1))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("system prompt secret"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("complete tool result secret"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("approval-token-secret"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("must-not-leak"))));

        verify(conversationService).listMessages("conv-1", OWNER, 99L, 20);
    }

    @Test
    void lifecycleEndpointsUseAuthenticatedOwnerAndReturnFrozenDtos() throws Exception {
        Conversation active = conversation("conv-1", OWNER, "Title", ConversationStatus.ACTIVE);
        Conversation archived = conversation("conv-1", OWNER, "Title", ConversationStatus.ARCHIVED);
        when(conversationService.requireOwned("conv-1", OWNER)).thenReturn(active);
        when(conversationService.archive("conv-1", OWNER)).thenReturn(archived);
        when(conversationService.restore("conv-1", OWNER)).thenReturn(active);

        mockMvc.perform(get("/api/ai/conversations/conv-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/ai/conversations/conv-1/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(post("/api/ai/conversations/conv-1/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(delete("/api/ai/conversations/conv-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        verify(conversationService).requireOwned("conv-1", OWNER);
        verify(conversationService).archive("conv-1", OWNER);
        verify(conversationService).restore("conv-1", OWNER);
        verify(conversationService).delete("conv-1", OWNER);
    }

    @Test
    void conflictArchivedAndInvalidRequestsUseRealHttpStatusesAndErrorCodes() throws Exception {
        when(conversationService.archive("conv-conflict", OWNER)).thenThrow(
                new ConversationMemoryException(
                        ConversationErrorCode.CONVERSATION_CONFLICT,
                        "Conversation status changed concurrently"));
        when(conversationService.restore("conv-archived", OWNER)).thenThrow(
                new ConversationMemoryException(
                        ConversationErrorCode.CONVERSATION_ARCHIVED,
                        "Conversation is archived"));

        mockMvc.perform(post("/api/ai/conversations/conv-conflict/archive"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONVERSATION_CONFLICT"));
        mockMvc.perform(post("/api/ai/conversations/conv-archived/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONVERSATION_ARCHIVED"));
        mockMvc.perform(get("/api/ai/conversations").param("status", "DELETED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CONVERSATION_REQUEST_INVALID"));
        mockMvc.perform(get("/api/ai/conversations").param("limit", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CONVERSATION_REQUEST_INVALID"));
        mockMvc.perform(get("/api/ai/conversations/conv-1/messages").param("beforeSequence", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CONVERSATION_REQUEST_INVALID"));
    }

    @Test
    void missingAuthenticationReturnsAReal401() throws Exception {
        when(requestUserContext.getRequiredUserId()).thenThrow(new SecurityException("not authenticated"));

        mockMvc.perform(get("/api/ai/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void runtimeCleanupFailureReturnsStable503WithoutClaimingDeletion() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeStateException.Unavailable(
                        "delete conversation derivatives", new IllegalStateException("Redis unavailable")))
                .when(conversationService).delete("conv-1", OWNER);

        mockMvc.perform(delete("/api/ai/conversations/conv-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("RUNTIME_STATE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "Conversation runtime cleanup is temporarily unavailable"))
                .andExpect(jsonPath("$.deleted").doesNotExist());
    }

    private static Conversation conversation(
            String id,
            String userId,
            String title,
            ConversationStatus status
    ) {
        return new Conversation(id, userId, title, status, 9, NOW, NOW, null, 0);
    }

    private static ConversationMessage message(
            long sequence,
            ConversationRole role,
            ConversationMessageType messageType,
            String content,
            String traceId,
            Map<String, Object> metadata
    ) {
        return new ConversationMessage(
                "message-" + sequence,
                "conv-1",
                sequence,
                "turn-" + sequence,
                "request-" + sequence,
                0,
                role,
                messageType,
                content,
                traceId,
                messageType == ConversationMessageType.TOOL_CALL
                        || messageType == ConversationMessageType.TOOL_RESULT
                        || role == ConversationRole.TOOL ? "tool-call-" + sequence : null,
                metadata,
                "hash-" + sequence,
                NOW.plusSeconds(sequence));
    }
}
