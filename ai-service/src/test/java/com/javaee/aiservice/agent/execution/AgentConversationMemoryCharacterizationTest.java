package com.javaee.aiservice.agent.execution;

import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.model.AgentToolResult;
import com.javaee.aiservice.agent.execution.reflection.AgentReflectionService;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import com.javaee.aiservice.conversation.ContextManager;
import com.javaee.aiservice.conversation.ConversationManager;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.internal.InternalService;
import com.javaee.aiservice.memory.context.MemoryCallStage;
import com.javaee.aiservice.memory.context.MemoryContext;
import com.javaee.aiservice.memory.context.MemoryContextBuilder;
import com.javaee.aiservice.memory.context.MemoryContextException;
import com.javaee.aiservice.memory.context.MemoryContextRequest;
import com.javaee.aiservice.memory.context.MemoryTurnContext;
import com.javaee.aiservice.security.RequestUserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentConversationMemoryCharacterizationTest {

    @Test
    void oversizedContextKeepsTheUserMessageAndAppendsARedactedFailureEvent() {
        ChatService chatService = mock(ChatService.class);
        ConversationManager conversationManager = mock(ConversationManager.class);
        ContextManager contextManager = mock(ContextManager.class);
        MemoryContextBuilder memoryBuilder = mock(MemoryContextBuilder.class);
        when(contextManager.getContext("conv-large")).thenReturn(new HashMap<>());
        when(memoryBuilder.build(any())).thenThrow(new MemoryContextException("too large"));
        AgentExecutionService service = configuredService(
                chatService, conversationManager, contextManager, memoryBuilder);

        assertThatThrownBy(() -> service.execute(request("conv-large", "large input")))
                .isInstanceOfSatisfying(MemoryContextException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MEMORY_CONTEXT_TOO_LARGE"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageAppendCommand>> writes = ArgumentCaptor.forClass(List.class);
        verify(conversationManager, times(2))
                .appendMessagesForUser(eq("conv-large"), eq("user-1"), writes.capture());
        assertThat(writes.getAllValues().get(0)).singleElement()
                .extracting(MessageAppendCommand::messageType)
                .isEqualTo(ConversationMessageType.TEXT);
        assertThat(writes.getAllValues().get(1)).singleElement().satisfies(command -> {
            assertThat(command.messageType()).isEqualTo(ConversationMessageType.EVENT);
            assertThat(command.content()).doesNotContain("large input", "too large");
            assertThat(command.metadata()).containsEntry("errorCode", "MEMORY_CONTEXT_TOO_LARGE");
        });
        verifyNoInteractions(chatService);
    }

    @Test
    void directAnswerAndPlannerUseTheSameBoundedConversationMemoryBuilder() {
        ChatService chatService = mock(ChatService.class);
        ConversationManager conversationManager = mock(ConversationManager.class);
        ContextManager contextManager = mock(ContextManager.class);
        MemoryContextBuilder memoryBuilder = stageMarkerBuilder();
        AgentExecutionService service = configuredService(
                chatService, conversationManager, contextManager, memoryBuilder);
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

        Map<String, Object> response = service.execute(request("conv-1", "What did I ask now?"));

        assertThat(response.get("answer")).isEqualTo("current-only answer");
        assertThat(prompts).containsExactly("bounded-PLANNER", "bounded-DIRECT_ANSWER");
        ArgumentCaptor<MemoryContextRequest> requests = ArgumentCaptor.forClass(MemoryContextRequest.class);
        verify(memoryBuilder, times(2)).build(requests.capture());
        assertThat(requests.getAllValues()).extracting(MemoryContextRequest::stage)
                .containsExactly(MemoryCallStage.PLANNER, MemoryCallStage.DIRECT_ANSWER);
        verify(conversationManager, atLeastOnce())
                .appendMessagesForUser(eq("conv-1"), eq("user-1"), any());
        InOrder durableBeforeModel = inOrder(conversationManager, chatService);
        durableBeforeModel.verify(conversationManager)
                .appendMessagesForUser(eq("conv-1"), eq("user-1"), any());
        durableBeforeModel.verify(chatService).callChatApiWithModelCode(any(), any());
    }

    @Test
    void plannerReflectionAndFinalSynthesisShareTheUnifiedMemoryBoundary() {
        AgentExecutionRequest request = request("conv-1", "current task");
        request.setMemoryTurnContext(new MemoryTurnContext(
                "conv-1", "user-1", "trace-1", "turn-1", "request-1", 7));
        MemoryContextBuilder memoryBuilder = stageMarkerBuilder();

        AgentExecutionService plannerService = new AgentExecutionService();
        ReflectionTestUtils.setField(plannerService, "toolRegistry", new AgentToolRegistry());
        ReflectionTestUtils.setField(plannerService, "memoryContextBuilder", memoryBuilder);
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
                3,
                "bounded-REFLECTION");
        ArgumentCaptor<String> reflectionPrompt = ArgumentCaptor.forClass(String.class);
        verify(reflectionChat).callChatApiWithModelCode(reflectionPrompt.capture(), any());

        ChatService finalChat = mock(ChatService.class);
        when(finalChat.callChatApiWithModelCode(any(), any())).thenReturn("final answer");
        AgentExecutionService finalService = new AgentExecutionService();
        ReflectionTestUtils.setField(finalService, "chatService", finalChat);
        ReflectionTestUtils.setField(finalService, "memoryContextBuilder", memoryBuilder);
        String finalAnswer = ReflectionTestUtils.invokeMethod(
                finalService,
                "synthesizeAnswer",
                request,
                List.of(),
                List.of(),
                Map.of("finalContext", "final-value"),
                false);
        ArgumentCaptor<String> finalPrompt = ArgumentCaptor.forClass(String.class);
        verify(finalChat).callChatApiWithModelCode(finalPrompt.capture(), any());

        assertThat(finalAnswer).isEqualTo("final answer");
        assertThat(plannerPrompt).isEqualTo("bounded-PLANNER");
        assertThat(reflectionPrompt.getValue()).isEqualTo("bounded-REFLECTION");
        assertThat(finalPrompt.getValue()).isEqualTo("bounded-FINAL_SYNTHESIS");
    }

    @Test
    void runtimeStateCanReachModelsOnlyThroughTheMemoryBuilderPolicy() {
        ChatService chatService = mock(ChatService.class);
        ConversationManager conversationManager = mock(ConversationManager.class);
        ContextManager contextManager = mock(ContextManager.class);
        MemoryContextBuilder memoryBuilder = mock(MemoryContextBuilder.class);
        AgentExecutionService service = configuredService(
                chatService, conversationManager, contextManager, memoryBuilder);
        when(memoryBuilder.build(any())).thenReturn(memory("policy-filtered-runtime"));
        when(contextManager.getContext("conv-stale")).thenReturn(new HashMap<>(Map.of(
                "documentId", "stale-doc",
                "selectedText", "stale selection")));
        List<String> prompts = new ArrayList<>();
        AtomicInteger invocation = new AtomicInteger();
        when(chatService.callChatApiWithModelCode(any(), any())).thenAnswer(call -> {
            prompts.add(call.getArgument(0));
            if (invocation.getAndIncrement() == 0) {
                return """
                        [{"id":"answer","description":"answer","toolName":"direct-answer",
                          "params":{"question":"new unrelated task"},"reasoning":"answer directly"}]
                        """;
            }
            return "done";
        });

        service.execute(request("conv-stale", "new unrelated task"));

        assertThat(prompts).allMatch("policy-filtered-runtime"::equals);
        ArgumentCaptor<MemoryContextRequest> memoryRequest = ArgumentCaptor.forClass(MemoryContextRequest.class);
        verify(memoryBuilder, atLeastOnce()).build(memoryRequest.capture());
        assertThat(memoryRequest.getAllValues().get(0).runtimeState())
                .containsEntry("documentId", "stale-doc")
                .containsEntry("selectedText", "stale selection");
    }

    private static AgentExecutionService configuredService(
            ChatService chatService,
            ConversationManager conversationManager,
            ContextManager contextManager,
            MemoryContextBuilder memoryBuilder
    ) {
        AgentExecutionService service = new AgentExecutionService();
        InternalService internalService = mock(InternalService.class);
        RequestUserContext requestUserContext = mock(RequestUserContext.class);
        when(requestUserContext.getRequiredUserId()).thenReturn("user-1");
        when(requestUserContext.getCurrentRole()).thenReturn("USER");
        when(internalService.hasPermission(any(), any())).thenReturn(true);
        doNothing().when(conversationManager).assertOwner(any(), eq("user-1"));
        doNothing().when(contextManager).updateContext(any(), any());
        doNothing().when(internalService).logAudit(any(), any(), any());

        ReflectionTestUtils.setField(service, "chatService", chatService);
        ReflectionTestUtils.setField(service, "conversationManager", conversationManager);
        ReflectionTestUtils.setField(service, "contextManager", contextManager);
        ReflectionTestUtils.setField(service, "internalService", internalService);
        ReflectionTestUtils.setField(service, "toolRegistry", new AgentToolRegistry());
        ReflectionTestUtils.setField(service, "requestUserContext", requestUserContext);
        ReflectionTestUtils.setField(service, "taskRegistry", new AgentTaskRegistry());
        ReflectionTestUtils.setField(service, "memoryContextBuilder", memoryBuilder);
        return service;
    }

    private static MemoryContextBuilder stageMarkerBuilder() {
        MemoryContextBuilder builder = mock(MemoryContextBuilder.class);
        when(builder.build(any())).thenAnswer(call -> {
            MemoryContextRequest memoryRequest = call.getArgument(0);
            return memory("bounded-" + memoryRequest.stage().name());
        });
        return builder;
    }

    private static MemoryContext memory(String rendered) {
        return new MemoryContext(rendered, 1, 100, 0, List.of(), List.of(), List.of(), Map.of());
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
