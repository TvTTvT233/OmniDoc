package com.javaee.aiservice.memory;

import com.javaee.aiservice.conversation.ConversationPersistenceTestFixture;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.runtime.RuntimeStateField;
import com.javaee.aiservice.conversation.runtime.RuntimeStateProperties;
import com.javaee.aiservice.conversation.runtime.RuntimeStateScope;
import com.javaee.aiservice.conversation.runtime.RuntimeStateService;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import com.javaee.aiservice.conversation.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

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

    @Disabled("M03: deletion must clear Runtime State, task snapshots and approval references")
    @Test
    void m03ConversationDeletionCascadesRuntimeDerivatives() {
        pending("M03 cascade cleanup");
    }

    @Disabled("M03: service-level continuation and approval cancellation must recheck ownership first")
    @Test
    void m03ContinuationAndApprovalCancellationEnforceOwnershipInsideService() {
        pending("M03 service-level owner checks");
    }

    @Disabled("M04: Planner, Direct Answer, Reflection, completion, Final and RAG Answer use one builder")
    @Test
    void m04AllConversationModelCallsUseMemoryContextBuilder() {
        pending("M04 unified model context");
    }

    @Disabled("M04: current user message appears once and recent history fills the remaining budget")
    @Test
    void m04CurrentMessageIsNotDuplicatedAndRecentHistoryIsContinuous() {
        pending("M04 context continuity");
    }

    @Disabled("M04: Tool Call and Tool Result are retained or trimmed as one atomic group")
    @Test
    void m04ToolCallAndResultAreNeverSplitByBudgeting() {
        pending("M04 atomic tool pair budgeting");
    }

    @Disabled("M04: oversized current input returns MEMORY_CONTEXT_TOO_LARGE without silent truncation")
    @Test
    void m04OversizedCurrentMessageFailsWithStableError() {
        pending("M04 token budget error");
    }

    @Disabled("M05: summary coverage and sourceDigest match the exact contiguous source messages")
    @Test
    void m05SummaryCoverageIsContinuousAndDigestVerified() {
        pending("M05 coverage and digest");
    }

    @Disabled("M05: assistant suggestions cannot become confirmed facts or decisions")
    @Test
    void m05SummaryPreservesSourceAndFactDecisionStatusRules() {
        pending("M05 fact accuracy");
    }

    @Disabled("M05: stale summary CAS cannot overwrite a newer summary version")
    @Test
    void m05ConcurrentSummaryUpdateRejectsStaleVersion() {
        pending("M05 summary CAS");
    }

    @Disabled("M05: schema/model failure preserves previous summary and does not advance coverage")
    @Test
    void m05SummaryFailureDoesNotAdvanceCoverage() {
        pending("M05 summary failure fallback");
    }

    private static void pending(String target) {
        fail("PENDING_IMPLEMENTATION: " + target);
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ConversationMemoryException.class)
                .satisfies(error -> assertThat(((ConversationMemoryException) error).getCode())
                        .isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
    }
}
