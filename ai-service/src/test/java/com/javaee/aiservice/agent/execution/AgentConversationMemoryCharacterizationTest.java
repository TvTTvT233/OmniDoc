package com.javaee.aiservice.agent.execution;

import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.model.AgentToolResult;
import com.javaee.aiservice.agent.execution.reflection.AgentReflectionService;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import com.javaee.aiservice.conversation.ContextManager;
import com.javaee.aiservice.conversation.ConversationManager;
import com.javaee.aiservice.internal.InternalService;
import com.javaee.aiservice.security.RequestUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConversationMemoryCharacterizationTest {

    @Test
    void directAnswerUsesOnlyCurrentQuestionAndNeverReadsSavedConversationHistory() {
        ChatService chatService = mock(ChatService.class);
        ConversationManager conversationManager = mock(ConversationManager.class);
        ContextManager contextManager = mock(ContextManager.class);
        AgentExecutionService service = configuredService(chatService, conversationManager, contextManager);
        when(conversationManager.getConversationHistoryForUser("conv-1", "user-1"))
                .thenReturn(List.of("User: remember marker", "Assistant: remembered"));
        when(contextManager.getContext("conv-1")).thenReturn(new HashMap<>());

        List<String> prompts = new ArrayList<>();
        AtomicInteger invocation = new AtomicInteger();
        when(chatService.callChatApiWithModelCode(any(), any())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            prompts.add(prompt);
            if (invocation.getAndIncrement() == 0) {
                throw new RuntimeException("planner unavailable");
            }
            return "current-only answer";
        });

        AgentExecutionRequest request = request("conv-1", "What did I ask now?");
        Map<String, Object> response = service.execute(request);

        assertThat(response.get("answer")).isEqualTo("current-only answer");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1))
                .isEqualTo("What did I ask now?")
                .doesNotContain("remember marker");
        verify(conversationManager, never()).getConversationHistory("conv-1");
        verify(conversationManager, never()).getConversationHistoryForUser("conv-1", "user-1");
        verify(conversationManager).addMessageForUser(
                "conv-1", "user-1", "What did I ask now?", "current-only answer");
    }

    @Test
    void plannerReflectionAndFinalSynthesisBuildIndependentPromptShapesWithoutHistory() {
        AgentExecutionRequest request = request("conv-1", "current task");
        String historyMarker = "prior-conversation-history-marker";

        AgentExecutionService plannerService = new AgentExecutionService();
        ReflectionTestUtils.setField(plannerService, "toolRegistry", new AgentToolRegistry());
        String plannerPrompt = ReflectionTestUtils.invokeMethod(
                plannerService, "buildPlannerPrompt", request, Map.of("plannerContext", "planner-value"));

        ChatService reflectionChat = mock(ChatService.class);
        when(reflectionChat.callChatApiWithModelCode(any(), any())).thenReturn("""
                {"complete":true,"continueExecution":false,"requiresReplan":false,
                 "shouldAskUser":false,"reason":"done","revisedPlan":[]}
                """);
        AgentReflectionService reflectionService = new AgentReflectionService();
        ReflectionTestUtils.setField(reflectionService, "chatService", reflectionChat);
        ReflectionTestUtils.setField(reflectionService, "toolRegistry", new AgentToolRegistry());
        reflectionService.reflect(
                request,
                List.of(),
                List.of(AgentToolResult.success("rag-search", "ok", Map.of("results", List.of()))),
                Map.of("reflectionContext", "reflection-value"),
                1,
                3
        );
        org.mockito.ArgumentCaptor<String> reflectionPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reflectionChat).callChatApiWithModelCode(reflectionPrompt.capture(), any());

        ChatService finalChat = mock(ChatService.class);
        when(finalChat.callChatApiWithModelCode(any(), any())).thenReturn("final answer");
        AgentExecutionService finalService = new AgentExecutionService();
        ReflectionTestUtils.setField(finalService, "chatService", finalChat);
        String finalAnswer = ReflectionTestUtils.invokeMethod(
                finalService,
                "synthesizeAnswer",
                request,
                List.of(),
                List.of(),
                Map.of("finalContext", "final-value"),
                false
        );
        org.mockito.ArgumentCaptor<String> finalPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(finalChat).callChatApiWithModelCode(finalPrompt.capture(), any());

        assertThat(finalAnswer).isEqualTo("final answer");
        assertThat(plannerPrompt).contains("plannerContext", "planner-value", "用户任务");
        assertThat(reflectionPrompt.getValue()).contains("reflectionContext", "reflection-value", "反思规则");
        assertThat(finalPrompt.getValue()).contains("finalContext", "final-value", "执行计划", "工具结果");
        assertThat(List.of(plannerPrompt, reflectionPrompt.getValue(), finalPrompt.getValue()))
                .allSatisfy(prompt -> assertThat(prompt).doesNotContain(historyMarker));
        assertThat(plannerPrompt).isNotEqualTo(reflectionPrompt.getValue());
        assertThat(reflectionPrompt.getValue()).isNotEqualTo(finalPrompt.getValue());
    }

    @Test
    void staleRedisContextFlowsIntoTheNextPlannerAndIsWrittenBackAcrossTurns() {
        ChatService chatService = mock(ChatService.class);
        ConversationManager conversationManager = mock(ConversationManager.class);
        ContextManager contextManager = mock(ContextManager.class);
        AgentExecutionService service = configuredService(chatService, conversationManager, contextManager);
        when(contextManager.getContext("conv-stale")).thenReturn(new HashMap<>(Map.of(
                "documentId", "stale-doc",
                "selectedText", "stale selection"
        )));
        List<String> prompts = new ArrayList<>();
        AtomicInteger invocation = new AtomicInteger();
        when(chatService.callChatApiWithModelCode(any(), any())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            prompts.add(prompt);
            if (invocation.getAndIncrement() == 0) {
                return """
                        [{"id":"answer","description":"answer","toolName":"direct-answer",
                          "params":{"question":"new unrelated task"},"reasoning":"answer directly"}]
                        """;
            }
            return "done";
        });

        service.execute(request("conv-stale", "new unrelated task"));

        assertThat(prompts.get(0)).contains("stale-doc", "stale selection", "new unrelated task");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> storedContext =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(contextManager).updateContext(eq("conv-stale"), storedContext.capture());
        assertThat(storedContext.getValue())
                .containsEntry("documentId", "stale-doc")
                .containsEntry("selectedText", "stale selection");
    }

    private static AgentExecutionService configuredService(
            ChatService chatService,
            ConversationManager conversationManager,
            ContextManager contextManager
    ) {
        AgentExecutionService service = new AgentExecutionService();
        InternalService internalService = mock(InternalService.class);
        RequestUserContext requestUserContext = mock(RequestUserContext.class);
        when(requestUserContext.getRequiredUserId()).thenReturn("user-1");
        when(requestUserContext.getCurrentRole()).thenReturn("USER");
        when(internalService.hasPermission(any(), any())).thenReturn(true);
        doNothing().when(conversationManager).assertOwner(any(), eq("user-1"));
        doNothing().when(conversationManager).addMessageForUser(any(), eq("user-1"), any(), any());
        doNothing().when(contextManager).updateContext(any(), any());
        doNothing().when(internalService).logAudit(any(), any(), any());

        ReflectionTestUtils.setField(service, "chatService", chatService);
        ReflectionTestUtils.setField(service, "conversationManager", conversationManager);
        ReflectionTestUtils.setField(service, "contextManager", contextManager);
        ReflectionTestUtils.setField(service, "internalService", internalService);
        ReflectionTestUtils.setField(service, "toolRegistry", new AgentToolRegistry());
        ReflectionTestUtils.setField(service, "requestUserContext", requestUserContext);
        ReflectionTestUtils.setField(service, "taskRegistry", new AgentTaskRegistry());
        return service;
    }

    private static AgentExecutionRequest request(String conversationId, String task) {
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setConversationId(conversationId);
        request.setTask(task);
        request.setContext(Map.of());
        request.setReflectionEnabled(false);
        request.setAutoReplan(false);
        return request;
    }
}
