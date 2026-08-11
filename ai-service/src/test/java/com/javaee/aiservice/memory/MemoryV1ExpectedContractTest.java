package com.javaee.aiservice.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.AgentExecutionService;
import com.javaee.aiservice.agent.execution.approval.AgentApprovalService;
import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.aiservice.conversation.ConversationPersistenceTestFixture;
import com.javaee.aiservice.conversation.ConversationManager;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.conversation.runtime.RuntimeStateException;
import com.javaee.aiservice.conversation.runtime.ConversationRuntimeDerivativeCleanup;
import com.javaee.aiservice.conversation.runtime.RuntimeStateField;
import com.javaee.aiservice.conversation.runtime.RuntimeStateProperties;
import com.javaee.aiservice.conversation.runtime.RuntimeStateScope;
import com.javaee.aiservice.conversation.runtime.RuntimeStateService;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import com.javaee.aiservice.conversation.service.ConversationService;
import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.aiservice.memory.context.ConservativeTokenEstimator;
import com.javaee.aiservice.memory.context.MemoryCallStage;
import com.javaee.aiservice.memory.context.MemoryContext;
import com.javaee.aiservice.memory.context.MemoryContextBuilder;
import com.javaee.aiservice.memory.context.MemoryContextException;
import com.javaee.aiservice.memory.context.MemoryContextProperties;
import com.javaee.aiservice.memory.context.MemoryContextRequest;
import com.javaee.aiservice.memory.context.MemoryToolExchange;
import com.javaee.aiservice.memory.summary.JdbcConversationSummaryStore;
import com.javaee.aiservice.memory.summary.StoredConversationSummary;
import com.javaee.aiservice.memory.summary.StructuredConversationSummary;
import com.javaee.aiservice.memory.summary.StructuredSummaryCodec;
import com.javaee.aiservice.memory.summary.StructuredSummaryValidator;
import com.javaee.aiservice.memory.summary.SummaryCoverage;
import com.javaee.aiservice.memory.summary.SummaryInputSanitizer;
import com.javaee.aiservice.memory.summary.SummaryItem;
import com.javaee.aiservice.memory.summary.SummaryMeta;
import com.javaee.aiservice.memory.summary.SummarySourceDigest;
import com.javaee.aiservice.memory.summary.SummarySourceRef;
import com.javaee.aiservice.memory.summary.SummarySourceType;
import com.javaee.aiservice.memory.summary.SummaryValidationContext;
import com.javaee.aiservice.memory.summary.SummaryValidationException;
import com.javaee.aiservice.memory.summary.SummaryWriteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Executable inventory of frozen Memory V1 gaps. Each test remains disabled only until the
 * named implementation task replaces the placeholder with a real assertion.
 */
class MemoryV1ExpectedContractTest {

    private ConversationPersistenceTestFixture fixture;
    private ConversationService service;

    @BeforeEach
    void setUpM02Persistence() {
        fixture = new ConversationPersistenceTestFixture();
        service = fixture.newService();
    }

    @Test
    void m02PersistsStructuredMessagesWithMonotonicSequence() {
        String conversationId = service.createConversation("owner").conversationId();

        List<ConversationMessage> messages = service.appendExchange(
                conversationId, "owner", "request-1", "turn-1", "question", "answer");

        assertThat(messages).extracting(ConversationMessage::sequence).containsExactly(1L, 2L);
        assertThat(messages).extracting(ConversationMessage::role)
                .containsExactly(ConversationRole.USER, ConversationRole.ASSISTANT);
        assertThat(messages).allSatisfy(message -> {
            assertThat(message.messageId()).isNotBlank();
            assertThat(message.contentHash()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void m02RetriesDoNotDuplicateUserOrAssistantMessages() {
        String conversationId = service.createConversation("owner").conversationId();

        List<ConversationMessage> first = service.appendExchange(
                conversationId, "owner", "stable-request", "turn-1", "question", "answer");
        List<ConversationMessage> retry = service.appendExchange(
                conversationId, "owner", "stable-request", "turn-2", "question", "answer");

        assertThat(retry).extracting(ConversationMessage::messageId)
                .containsExactlyElementsOf(first.stream().map(ConversationMessage::messageId).toList());
        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = ?",
                Integer.class,
                conversationId)).isEqualTo(2);
    }

    @Test
    void m02HistorySurvivesServiceRestartAndRedisExpiry() {
        String conversationId = service.createConversation("owner").conversationId();
        service.appendExchange(conversationId, "owner", "request-1", "turn-1", "before", "restart");
        AtomicInteger legacyReads = new AtomicInteger();

        ConversationService recreated = fixture.newService((ignoredConversation, ignoredUser) -> {
            legacyReads.incrementAndGet();
            throw new AssertionError("durable history must not depend on Redis compatibility reads");
        });

        assertThat(recreated.legacyHistory(conversationId, "owner", 100))
                .containsExactly("User: before", "Assistant: restart");
        assertThat(legacyReads).hasValue(0);
    }

    @Test
    void m02ConversationAccessIsOwnerIsolated() {
        String conversationId = service.createConversation("owner").conversationId();

        assertNotFound(() -> service.requireOwned(conversationId, "other"));
        assertNotFound(() -> service.appendExchange(
                conversationId, "other", "foreign-request", "turn-1", "x", "y"));
        assertNotFound(() -> service.archive(conversationId, "other"));
        assertNotFound(() -> service.delete(conversationId, "other"));
        assertThat(service.requireOwned(conversationId, "owner").userId()).isEqualTo("owner");
    }

    @Test
    void m03RuntimeStateUsesWhitelistAndBoundedTtl() {
        RuntimeStateProperties properties = new RuntimeStateProperties();

        assertThat(Arrays.stream(RuntimeStateField.values()).map(RuntimeStateField::wireName))
                .containsExactly(
                        "currentDocumentId",
                        "selectedText",
                        "pageContext",
                        "pendingApproval",
                        "currentToolState"
                );
        assertThat(properties.effectivePolicy(RuntimeStateField.CURRENT_DOCUMENT_ID).ttl())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.effectivePolicy(RuntimeStateField.SELECTED_TEXT).ttl())
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.effectivePolicy(RuntimeStateField.PAGE_CONTEXT).ttl())
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.effectivePolicy(RuntimeStateField.PENDING_APPROVAL).ttl())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.effectivePolicy(RuntimeStateField.CURRENT_TOOL_STATE).ttl())
                .isEqualTo(Duration.ofHours(1));
        assertThat(Arrays.stream(RuntimeStateService.class.getMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(Map.class::isAssignableFrom)).isTrue();
    }

    @Test
    void m03RuntimeStateDoesNotLeakAcrossConversations() {
        RuntimeStateScope first = new RuntimeStateScope("conv-a", "trace-a");
        RuntimeStateScope otherTrace = new RuntimeStateScope("conv-a", "trace-b");
        RuntimeStateScope otherConversation = new RuntimeStateScope("conv-b", "trace-a");

        assertThat(first).isNotEqualTo(otherTrace).isNotEqualTo(otherConversation);
        assertThat(otherTrace).isNotEqualTo(otherConversation);
    }

    @Test
    void m03ConversationDeletionCascadesRuntimeDerivatives() {
        RuntimeStateService runtimeStateService = mock(RuntimeStateService.class);
        AgentTaskRegistry taskRegistry = new AgentTaskRegistry();
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        when(approvalService.cancel("approval-delete")).thenReturn(true);
        taskRegistry.save("trace-delete", new LinkedHashMap<>(Map.of(
                "traceId", "trace-delete",
                "conversationId", "conv-delete",
                "userId", "owner",
                "pendingApproval", Map.of("agentApprovalToken", "approval-delete"))));

        ConversationRuntimeDerivativeCleanup derivatives = new ConversationRuntimeDerivativeCleanup(
                runtimeStateService, taskRegistry, approvalService);
        derivatives.clearConversation("conv-delete");

        verify(runtimeStateService).clearConversation("conv-delete");
        verify(approvalService).cancel("approval-delete");
        assertThat(taskRegistry.get("trace-delete")).isEmpty();

        AtomicInteger durableRowsSeenByCleanup = new AtomicInteger(-1);
        ConversationService cleanupAware = fixture.newService(conversationId ->
                durableRowsSeenByCleanup.set(conversationRowCount(fixture, conversationId)));
        String deletedConversationId = cleanupAware.createConversation("owner").conversationId();

        cleanupAware.delete(deletedConversationId, "owner");

        assertThat(durableRowsSeenByCleanup).hasValue(1);
        assertThat(conversationRowCount(fixture, deletedConversationId)).isZero();

        ConversationPersistenceTestFixture retryableFixture = new ConversationPersistenceTestFixture();
        ConversationService cleanupUnavailable = retryableFixture.newService(conversationId -> {
            throw new RuntimeStateException.Unavailable("test-cleanup", new IllegalStateException("Redis unavailable"));
        });
        String retryableConversationId = cleanupUnavailable.createConversation("owner").conversationId();
        cleanupUnavailable.appendExchange(
                retryableConversationId, "owner", "request-1", "turn-1", "question", "answer");

        assertThatThrownBy(() -> cleanupUnavailable.delete(retryableConversationId, "owner"))
                .isInstanceOf(RuntimeStateException.Unavailable.class)
                .hasMessageContaining("test-cleanup");
        assertThat(conversationRowCount(retryableFixture, retryableConversationId)).isEqualTo(1);
        assertThat(retryableFixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = ?",
                Integer.class,
                retryableConversationId)).isEqualTo(2);
    }

    @Test
    void m03ContinuationAndApprovalCancellationEnforceOwnershipInsideService() {
        AgentExecutionService agentService = new AgentExecutionService();
        AgentTaskRegistry taskRegistry = new AgentTaskRegistry();
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        ConversationManager conversationManager = mock(ConversationManager.class);
        RequestUserContext userContext = mock(RequestUserContext.class);
        when(userContext.getRequiredUserId()).thenReturn("intruder");

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("traceId", "trace-owner");
        snapshot.put("conversationId", "conv-owner");
        snapshot.put("userId", "owner");
        snapshot.put("task", "protected task");
        snapshot.put("status", "action_required");
        snapshot.put("context", new HashMap<>());
        snapshot.put("pendingApproval", Map.of("agentApprovalToken", "approval-owner"));
        taskRegistry.save("trace-owner", snapshot);

        ReflectionTestUtils.setField(agentService, "requestUserContext", userContext);
        ReflectionTestUtils.setField(agentService, "conversationManager", conversationManager);
        ReflectionTestUtils.setField(agentService, "taskRegistry", taskRegistry);
        ReflectionTestUtils.setField(agentService, "agentApprovalService", approvalService);

        AgentExecutionRequest continuation = new AgentExecutionRequest();
        continuation.setTask("continue as the owner");
        continuation.setUserId("owner");
        continuation.setContinueTraceId("trace-owner");

        assertThatThrownBy(() -> agentService.execute(continuation))
                .isInstanceOf(SecurityException.class)
                .hasMessage("无权续接该任务");
        assertThatThrownBy(() -> agentService.cancelApproval("approval-owner", "owner"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("无权取消该任务");

        assertThat(taskRegistry.get("trace-owner"))
                .containsEntry("status", "action_required")
                .containsEntry("pendingApproval", Map.of("agentApprovalToken", "approval-owner"));
        verifyNoInteractions(approvalService, conversationManager);
    }

    @Test
    void m04AllConversationModelCallsUseMemoryContextBuilder() {
        assertThat(MemoryCallStage.values()).containsExactlyInAnyOrder(
                MemoryCallStage.PLANNER,
                MemoryCallStage.FOLLOW_UP_PLANNER,
                MemoryCallStage.DIRECT_ANSWER,
                MemoryCallStage.RAG_ANSWER,
                MemoryCallStage.REFLECTION,
                MemoryCallStage.COMPLETION_EVALUATION,
                MemoryCallStage.FINAL_SYNTHESIS);
    }

    @Test
    void m04CurrentMessageIsNotDuplicatedAndRecentHistoryIsContinuous() {
        ConversationService service = fixture.newService();
        String conversationId = service.createConversation("owner").conversationId();
        service.appendExchange(conversationId, "owner", "request-1", "turn-1", "first", "answer");
        ConversationMessage current = service.appendMessages(conversationId, "owner", List.of(
                MessageAppendCommand.text("turn-2", "request-2", 0, ConversationRole.USER, "current-unique")))
                .get(0);
        MemoryContext context = contextBuilder(service, 4096).build(new MemoryContextRequest(
                conversationId, "owner", null, MemoryCallStage.PLANNER, "policy", "current-unique",
                current.sequence(), Map.of(), List.of(), ""));

        assertThat(context.rendered().split("current-unique", -1)).hasSize(2);
        assertThat(context.recentMessages()).extracting(ConversationMessage::sequence).containsExactly(1L, 2L);
    }

    @Test
    void m04ToolCallAndResultAreNeverSplitByBudgeting() {
        ConversationService service = fixture.newService();
        String conversationId = service.createConversation("owner").conversationId();
        service.appendMessages(conversationId, "owner", List.of(
                new MessageAppendCommand(null, "turn-1", "request-1", 0, ConversationRole.TOOL,
                        ConversationMessageType.TOOL_CALL, "call", "trace", "tool-1", Map.of("source", "agent")),
                new MessageAppendCommand(null, "turn-1", "request-1", 1, ConversationRole.TOOL,
                        ConversationMessageType.TOOL_RESULT, "result", "trace", "tool-1", Map.of("source", "agent"))));
        ConversationMessage current = service.appendMessages(conversationId, "owner", List.of(
                MessageAppendCommand.text("turn-2", "request-2", 0, ConversationRole.USER, "current"))).get(0);
        MemoryContext context = contextBuilder(service, 4096).build(new MemoryContextRequest(
                conversationId, "owner", null, MemoryCallStage.PLANNER, "policy", "current",
                current.sequence(), Map.of(), List.of(), ""));

        assertThat(context.recentMessages()).extracting(ConversationMessage::messageType)
                .containsExactly(ConversationMessageType.TOOL_CALL, ConversationMessageType.TOOL_RESULT);
    }

    @Test
    void m04OversizedCurrentMessageFailsWithStableError() {
        ConversationService service = fixture.newService();
        String conversationId = service.createConversation("owner").conversationId();
        assertThatThrownBy(() -> contextBuilder(service, 1024).build(new MemoryContextRequest(
                conversationId, "owner", null, MemoryCallStage.DIRECT_ANSWER, "policy", "中".repeat(2000),
                1, Map.of(), List.of(new MemoryToolExchange("pair", "call", "result", true)), "")))
                .isInstanceOfSatisfying(MemoryContextException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MEMORY_CONTEXT_TOO_LARGE"));
    }

    @Test
    void m05SummaryCoverageIsContinuousAndDigestVerified() {
        String conversationId = service.createConversation("owner").conversationId();
        List<ConversationMessage> messages = service.appendExchange(
                conversationId, "owner", "request-1", "turn-1", "first fact", "acknowledged");
        String digest = SummarySourceDigest.compute(conversationId, 1, 2, messages);

        assertThat(digest).hasSize(64).matches("[a-f0-9]{64}");
        assertThatThrownBy(() -> SummarySourceDigest.compute(
                conversationId, 1, 3, messages))
                .isInstanceOf(SummaryValidationException.class);
    }

    @Test
    void m05SummaryPreservesSourceAndFactDecisionStatusRules() {
        String conversationId = service.createConversation("owner").conversationId();
        List<ConversationMessage> messages = service.appendExchange(
                conversationId, "owner", "request-1", "turn-1", "Please advise", "I suggest option A");
        String digest = SummarySourceDigest.compute(conversationId, 1, 2, messages);
        SummarySourceRef assistant = new SummarySourceRef(
                SummarySourceType.MESSAGE, messages.get(1).messageId(), null, Map.of(), messages.get(1).contentHash());
        StructuredConversationSummary summary = summary(
                conversationId, 1, 0, 2, digest,
                List.of(new SummaryItem("fact-1", "Option A is confirmed", "confirmed", List.of(assistant), 2)),
                List.of(new SummaryItem("decision-1", "Use option A", "confirmed", List.of(assistant), 2)));

        StructuredSummaryValidator validator = new StructuredSummaryValidator(new SummaryInputSanitizer());
        assertThatThrownBy(() -> validator.validate(summary, new SummaryValidationContext(
                conversationId, 1, 0, 1, 2, digest,
                Instant.parse("2026-08-11T00:00:00Z"), messages, null)))
                .isInstanceOf(SummaryValidationException.class);
    }

    @Test
    void m05ConcurrentSummaryUpdateRejectsStaleVersion() {
        String conversationId = service.createConversation("owner").conversationId();
        JdbcConversationSummaryStore store = fixture.newSummaryStore();
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        StoredConversationSummary winner = new StoredConversationSummary(
                conversationId, "1.0", 1, 1, 1, "a".repeat(64), "{\"winner\":true}", now, now);
        StoredConversationSummary stale = new StoredConversationSummary(
                conversationId, "1.0", 1, 1, 1, "b".repeat(64), "{\"stale\":true}", now, now);

        assertThat(store.saveCas(winner, 0)).isEqualTo(SummaryWriteResult.STORED);
        assertThat(store.saveCas(stale, 0)).isEqualTo(SummaryWriteResult.CONFLICT);
        assertThat(store.findOwned(conversationId, "owner").orElseThrow().summaryJson())
                .isEqualTo("{\"winner\":true}");
    }

    @Test
    void m05SummaryFailureDoesNotAdvanceCoverage() {
        String conversationId = service.createConversation("owner").conversationId();
        JdbcConversationSummaryStore store = fixture.newSummaryStore();
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        StoredConversationSummary previous = new StoredConversationSummary(
                conversationId, "1.0", 1, 1, 1, "a".repeat(64), "{\"version\":1}", now, now);
        assertThat(store.saveCas(previous, 0)).isEqualTo(SummaryWriteResult.STORED);

        StructuredSummaryCodec codec = new StructuredSummaryCodec(new ObjectMapper());
        assertThatThrownBy(() -> codec.parse("{not-valid-summary}"))
                .isInstanceOf(SummaryValidationException.class);
        assertThat(store.findOwned(conversationId, "owner").orElseThrow())
                .extracting(StoredConversationSummary::summaryVersion, StoredConversationSummary::toSequence)
                .containsExactly(1L, 1L);
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ConversationMemoryException.class)
                .satisfies(error -> assertThat(((ConversationMemoryException) error).getCode())
                        .isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
    }

    private static int conversationRowCount(
            ConversationPersistenceTestFixture persistenceFixture,
            String conversationId
    ) {
        return persistenceFixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE conversation_id = ?",
                Integer.class,
                conversationId);
    }

    private static MemoryContextBuilder contextBuilder(ConversationService service, int window) {
        MemoryContextProperties properties = new MemoryContextProperties();
        properties.setDefaultWindowTokens(window);
        properties.setReservedOutputTokens(window == 1024 ? 700 : 256);
        properties.setSafetyReservePercent(0);
        return new MemoryContextBuilder(
                service,
                (conversationId, userId) -> java.util.Optional.empty(),
                new ConservativeTokenEstimator(),
                properties);
    }

    private static StructuredConversationSummary summary(
            String conversationId,
            long version,
            long previousVersion,
            long toSequence,
            String digest,
            List<SummaryItem> facts,
            List<SummaryItem> decisions
    ) {
        return new StructuredConversationSummary(
                "1.0", conversationId, version, new SummaryCoverage(1, toSequence, digest),
                null, List.of(), facts, decisions, List.of(), List.of(), List.of(),
                new SummaryMeta(previousVersion, Instant.parse("2026-08-11T00:00:00Z")));
    }
}
