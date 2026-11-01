package com.javaee.aiservice.taskrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.aiservice.async.*;
import com.javaee.aiservice.security.RequestUserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskRunProtocolTest {
    @Test void lifecycleKeepsLegacyFieldsAndRecordsSeparateAttemptsWithoutBodies() throws Exception {
        AgentTaskRegistry registry = new AgentTaskRegistry();
        String execution = registry.beginRun("t1", Map.of("userId", "owner", "task", "PRIVATE_TASK"), "j1");
        var adapter = new AgentTaskRunAdapter(registry);
        assertThat(adapter.findOwned("other", "t1")).isEmpty();
        assertThat(adapter.findOwned("owner", "t1").orElseThrow().status()).isEqualTo("RUNNING");
        String first = registry.beginTool("t1", execution, "s1", "document-read", 1);
        registry.finishTool("t1", first, "error");
        String second = registry.beginTool("t1", execution, "s1", "document-read", 2);
        registry.finishTool("t1", second, "success");
        registry.save("t1", Map.of("userId", "owner", "status", "action_required", "context", Map.of("token", "PRIVATE_TOKEN")));
        registry.finishRun("t1", execution, "action_required");
        var view = adapter.findOwned("owner", "t1").orElseThrow();
        assertThat(view.status()).isEqualTo("WAITING_INPUT");
        assertThat(view.finishedAt()).isNull();
        assertThat(view.toolCalls()).hasSize(2);
        assertThat(first).isNotEqualTo(second);
        assertThat(view.steps()).hasSize(1);
        assertThat(view.steps().get(0).attempt()).isEqualTo(2);
        assertThat(view.events()).hasSize(6);
        assertThat(view.links().get(0).runId()).isEqualTo("async:j1");
        assertThat(registry.get("t1")).containsKey("context");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(view);
        assertThat(json).doesNotContain("PRIVATE_TASK", "PRIVATE_TOKEN", "context", "approvalToken");
        String continued = registry.beginRun("t1", Map.of("userId", "owner"), null);
        assertThat(continued).isNotEqualTo(execution);
        assertThat(adapter.findOwned("owner", "t1").orElseThrow().attempt()).isEqualTo(2);
    }
    @Test void cancellationRequestIsNotCancellationCompletionAndHistoryIsBounded() {
        AgentTaskRegistry registry = new AgentTaskRegistry();
        assertThat(registry.cancel("missing")).isFalse();
        String execution = registry.beginRun("t1", Map.of("userId", "owner"), null);
        registry.cancel("t1");
        var adapter = new AgentTaskRunAdapter(registry);
        var pending = adapter.findOwned("owner", "t1").orElseThrow();
        assertThat(pending.status()).isEqualTo("RUNNING");
        assertThat(pending.cancellationRequested()).isTrue();
        for (int i = 0; i < 260; i++) registry.beginTool("t1", execution, "s1", "document-read", i + 1);
        registry.finishRun("t1", execution, "cancelled");
        var ended = adapter.findOwned("owner", "t1").orElseThrow();
        assertThat(ended.status()).isEqualTo("CANCELLED");
        assertThat(ended.events()).hasSize(256);
        assertThat(ended.capabilities().limitations()).contains("HISTORY_TRUNCATED");
    }
    @Test void legacySnapshotDoesNotInventHistoryOrTiming() {
        AgentTaskRegistry registry = new AgentTaskRegistry();
        registry.save("old", Map.of("userId", "owner", "status", "success"));
        var view = new AgentTaskRunAdapter(registry).findOwned("owner", "old").orElseThrow();
        assertThat(view.events()).isEmpty();
        assertThat(view.toolCalls()).isEmpty();
        assertThat(view.startedAt()).isNull();
        assertThat(view.attempt()).isNull();
    }
    @ParameterizedTest @CsvSource({"error,FAILED", "action_required,WAITING_INPUT", "planned,WAITING_INPUT", "success,SUCCEEDED", "unexpected,UNKNOWN"})
    void asyncHandlerReturnDoesNotOverrideAgentResult(String inner, String expected) {
        AsyncAIJobService jobs = mock(AsyncAIJobService.class);
        AsyncAIJobVO job = new AsyncAIJobVO();
        job.setJobId("j1"); job.setType("agent"); job.setStatus("success"); job.setUserId("owner");
        job.setResult(Map.of("status", inner, "traceId", "t1", "answer", "PRIVATE_ANSWER"));
        when(jobs.findOwned("j1", "owner")).thenReturn(Optional.of(job));
        var view = new AsyncTaskRunAdapter(jobs).findOwned("owner", "j1").orElseThrow();
        assertThat(view.status()).isEqualTo(expected);
        assertThat(view.sourceStatus()).isEqualTo("success");
        assertThat(view.links().get(0).runId()).isEqualTo("agent:t1");
        job.setStatus("failed");
        assertThat(new AsyncTaskRunAdapter(jobs).findOwned("owner", "j1").orElseThrow().status()).isEqualTo("FAILED");
    }
    @Test void controllerUsesAuthenticatedOwnerRejectsBadIdentifiersAndSanitizesErrors() throws Exception {
        RequestUserContext users = mock(RequestUserContext.class);
        AgentTaskRunAdapter agents = mock(AgentTaskRunAdapter.class);
        AsyncTaskRunAdapter async = mock(AsyncTaskRunAdapter.class);
        ReviewTaskRunAdapter reviews = mock(ReviewTaskRunAdapter.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TaskRunController(users, agents, async, reviews)).build();
        when(users.getCurrentUserId()).thenReturn(Optional.empty());
        mvc.perform(get("/api/ai/task-runs/agent:t1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(agents, async, reviews);
        when(users.getCurrentUserId()).thenReturn(Optional.of("owner"));
        mvc.perform(get("/api/ai/task-runs/agent:t1").param("userId", "other").param("admin", "true"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("TASK_RUN_NOT_FOUND"));
        verify(agents).findOwned("owner", "t1");
        mvc.perform(get("/api/ai/task-runs/agent:bad:extra")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ai/task-runs/review:j1")).andExpect(status().isBadRequest());
        when(agents.findOwned("owner", "t1")).thenThrow(new DataAccessResourceFailureException("PRIVATE_CONNECTION"));
        mvc.perform(get("/api/ai/task-runs/agent:t1")).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_CONNECTION"))));
    }
    @Test void publicHttpProjectionSerializesDatesAndRejectsAnotherOwnerEvenWhenAdminFlagIsSupplied() throws Exception {
        RequestUserContext users = mock(RequestUserContext.class);
        AgentTaskRegistry registry = new AgentTaskRegistry();
        String execution = registry.beginRun("trace", Map.of("userId", "owner", "task", "PRIVATE_TASK"), null);
        registry.finishRun("trace", execution, "success");
        var mvc = MockMvcBuilders.standaloneSetup(new TaskRunController(users, new AgentTaskRunAdapter(registry),
                mock(AsyncTaskRunAdapter.class), mock(ReviewTaskRunAdapter.class))).build();
        when(users.getCurrentUserId()).thenReturn(Optional.of("owner"));
        mvc.perform(get("/api/ai/task-runs/agent:trace"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runId").value("agent:trace"))
                .andExpect(jsonPath("$.task").doesNotExist()).andExpect(jsonPath("$.events.length()").value(2));
        when(users.getCurrentUserId()).thenReturn(Optional.of("other"));
        mvc.perform(get("/api/ai/task-runs/agent:trace").param("admin", "true").param("userId", "owner"))
                .andExpect(status().isNotFound());
    }
}
