package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.agent.execution.AgentExecutionService;
import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.aiservice.conversation.ConversationManager;
import com.javaee.aiservice.conversation.ContextManager;
import com.javaee.aiservice.security.RequestUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskRunExecutionHookTest {
    @Test void realEntryPublishesOwnerScopedRunningBeforeWorkAndRecordsThrownFailure() {
        AgentExecutionService service = new AgentExecutionService();
        AgentTaskRegistry registry = new AgentTaskRegistry();
        var adapter = new AgentTaskRunAdapter(registry);
        ConversationManager conversations = mock(ConversationManager.class);
        ContextManager context = mock(ContextManager.class);
        RequestUserContext users = mock(RequestUserContext.class);
        when(users.getRequiredUserId()).thenReturn("owner");
        when(users.getCurrentRole()).thenReturn("user");
        when(conversations.createConversation("owner")).thenReturn("conversation");
        when(context.getContext("conversation")).thenReturn(new HashMap<>());
        when(conversations.appendMessagesForUser(anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("PRIVATE_STORAGE_ERROR"));
        ReflectionTestUtils.setField(service, "taskRegistry", registry);
        ReflectionTestUtils.setField(service, "requestUserContext", users);
        ReflectionTestUtils.setField(service, "conversationManager", conversations);
        ReflectionTestUtils.setField(service, "contextManager", context);
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setTask("synthetic task");
        request.setContext(Map.of("asyncJobId", "client-forged-link"));
        AtomicReference<String> trace = new AtomicReference<>();
        assertThatThrownBy(() -> service.execute(request, "server-job", id -> {
            trace.set(id);
            assertThat(adapter.findOwned("other", id)).isEmpty();
            var running = adapter.findOwned("owner", id).orElseThrow();
            assertThat(running.status()).isEqualTo("RUNNING");
            assertThat(running.links().get(0).runId()).isEqualTo("async:server-job");
        })).isInstanceOf(IllegalStateException.class);
        var failed = adapter.findOwned("owner", trace.get()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.finishedAt()).isNotNull();
        assertThat(failed.events()).extracting(TaskRunView.Event::type).containsExactly("RUN_STARTED", "RUN_FINISHED");
        assertThat(failed.toolCalls()).isEmpty();
    }
}
