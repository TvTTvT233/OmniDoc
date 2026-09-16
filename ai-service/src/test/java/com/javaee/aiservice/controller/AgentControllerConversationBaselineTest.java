package com.javaee.aiservice.controller;

import com.javaee.aiservice.agent.KnowledgeIndexAgent;
import com.javaee.aiservice.agent.execution.AgentExecutionService;
import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.aiservice.conversation.ConversationManager;
import com.javaee.aiservice.security.RequestUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerConversationBaselineTest {

    private MockMvc mockMvc;
    private ConversationManager conversationManager;
    private AgentExecutionService executionService;

    @BeforeEach
    void setUp() {
        AgentController controller = new AgentController();
        conversationManager = mock(ConversationManager.class);
        executionService = mock(AgentExecutionService.class);
        RequestUserContext requestUserContext = mock(RequestUserContext.class);
        when(requestUserContext.getRequiredUserId()).thenReturn("authenticated-user");
        when(requestUserContext.getCurrentRole()).thenReturn("user");

        ReflectionTestUtils.setField(controller, "conversationManager", conversationManager);
        ReflectionTestUtils.setField(controller, "agentExecutionService", executionService);
        ReflectionTestUtils.setField(controller, "agentTaskRegistry", mock(AgentTaskRegistry.class));
        ReflectionTestUtils.setField(controller, "knowledgeIndexAgent", mock(KnowledgeIndexAgent.class));
        ReflectionTestUtils.setField(controller, "requestUserContext", requestUserContext);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void legacyStartIgnoresClientUserIdAndUsesAuthenticatedOwner() throws Exception {
        when(conversationManager.createConversation("authenticated-user")).thenReturn("conv-1");

        mockMvc.perform(post("/api/ai/agent/chat/start").param("userId", "forged-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("conv-1"));

        verify(conversationManager).createConversation("authenticated-user");
    }

    @Test
    void legacyHistoryReturnsFlatStringViewThroughOwnerCheckedFacade() throws Exception {
        when(conversationManager.getConversationHistoryForUser("conv-1", "authenticated-user"))
                .thenReturn(List.of("User: hello", "Assistant: hi"));

        mockMvc.perform(get("/api/ai/agent/chat/conv-1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", contains("User: hello", "Assistant: hi")));

        verify(conversationManager).getConversationHistoryForUser("conv-1", "authenticated-user");
    }

    @Test
    void legacyEndDelegatesToPermanentConversationDeletionOnly() throws Exception {
        mockMvc.perform(post("/api/ai/agent/chat/conv-1/end"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        verify(conversationManager).deleteConversationForUser("conv-1", "authenticated-user");
    }

    @Test
    void legacyChatPassesConversationIdAndAuthenticatedUserIntoUnifiedAgent() throws Exception {
        when(executionService.execute(any())).thenReturn(Map.of(
                "conversationId", "conv-1",
                "answer", "ok"
        ));

        mockMvc.perform(post("/api/ai/agent/chat")
                        .param("conversationId", "conv-1")
                        .contentType("text/plain")
                        .content("hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("ok"));

        org.mockito.ArgumentCaptor<AgentExecutionRequest> request =
                org.mockito.ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(executionService).execute(request.capture());
        assertThat(request.getValue().getConversationId()).isEqualTo("conv-1");
        assertThat(request.getValue().getUserId()).isEqualTo("authenticated-user");
        assertThat(request.getValue().getContext()).isEmpty();
    }
}
