package com.javaee.aiservice.memory.context;

import com.javaee.aiservice.conversation.ConversationPersistenceTestFixture;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.conversation.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryContextBuilderTest {

    private ConversationService conversations;
    private MemoryContextProperties properties;
    private String conversationId;

    @BeforeEach
    void setUp() {
        conversations = new ConversationPersistenceTestFixture().newService();
        properties = new MemoryContextProperties();
        properties.setDefaultWindowTokens(4096);
        properties.setReservedOutputTokens(256);
        properties.setSafetyReservePercent(0);
        conversationId = conversations.createConversation("owner").conversationId();
    }

    @Test
    void currentMessageAppearsOnceAndRecentHistoryIsContinuousAfterCoverage() {
        conversations.appendExchange(conversationId, "owner", "request-1", "turn-1", "alpha", "beta");
        conversations.appendExchange(conversationId, "owner", "request-2", "turn-2", "gamma", "delta");
        ConversationMessage current = conversations.appendMessages(
                conversationId,
                "owner",
                List.of(MessageAppendCommand.text(
                        "turn-3", "request-3", 0, ConversationRole.USER, "unique-current-message")))
                .get(0);
        MemoryContextBuilder builder = builder((id, owner) -> java.util.Optional.of(
                new MemorySummaryView(2, "summary through sequence 2")));

        MemoryContext context = builder.build(request(current.sequence(), List.of()));

        assertThat(occurrences(context.rendered(), "unique-current-message")).isOne();
        assertThat(context.summaryCoverageToSequence()).isEqualTo(2);
        assertThat(context.recentMessages()).extracting(ConversationMessage::sequence)
                .containsExactly(3L, 4L);
        assertThat(context.rendered()).doesNotContain("alpha", "beta").contains("gamma", "delta");
        assertThat(context.estimatedTokens()).isLessThanOrEqualTo(context.inputBudgetTokens());
    }

    @Test
    void toolCallAndResultAreIncludedOrTrimmedAsOneAtomicUnit() {
        conversations.appendMessages(conversationId, "owner", List.of(
                tool("turn-1", "request-1", 0, ConversationMessageType.TOOL_CALL, "call", "tool-1"),
                tool("turn-1", "request-1", 1, ConversationMessageType.TOOL_RESULT, "result", "tool-1")));
        ConversationMessage current = conversations.appendMessages(
                conversationId, "owner", List.of(MessageAppendCommand.text(
                        "turn-2", "request-2", 0, ConversationRole.USER, "current"))).get(0);

        MemoryContext enough = builder((id, owner) -> java.util.Optional.empty())
                .build(request(current.sequence(), List.of()));
        assertThat(enough.recentMessages()).extracting(ConversationMessage::messageType)
                .containsExactly(ConversationMessageType.TOOL_CALL, ConversationMessageType.TOOL_RESULT);

        MemoryContextProperties tight = new MemoryContextProperties();
        tight.setDefaultWindowTokens(1024);
        tight.setReservedOutputTokens(650);
        tight.setSafetyReservePercent(0);
        String largeCall = "x".repeat(900);
        String largeResult = "y".repeat(900);
        ConversationService other = new ConversationPersistenceTestFixture().newService();
        String otherId = other.createConversation("owner").conversationId();
        other.appendMessages(otherId, "owner", List.of(
                tool("turn-a", "request-a", 0, ConversationMessageType.TOOL_CALL, largeCall, "tool-large"),
                tool("turn-a", "request-a", 1, ConversationMessageType.TOOL_RESULT, largeResult, "tool-large")));
        ConversationMessage otherCurrent = other.appendMessages(otherId, "owner", List.of(
                MessageAppendCommand.text("turn-b", "request-b", 0, ConversationRole.USER, "current"))).get(0);
        MemoryContextBuilder tightBuilder = new MemoryContextBuilder(
                other, (id, owner) -> java.util.Optional.empty(), new ConservativeTokenEstimator(), tight);
        MemoryContext trimmed = tightBuilder.build(new MemoryContextRequest(
                otherId, "owner", null, MemoryCallStage.PLANNER, "policy", "current",
                otherCurrent.sequence(), Map.of(), List.of(), ""));

        assertThat(trimmed.recentMessages()).isEmpty();
        assertThat(trimmed.trimmingReasons()).contains("OLDER_HISTORY_TRIMMED_BY_BUDGET");
    }

    @Test
    void oversizedCurrentMessageFailsWithoutSilentTruncation() {
        MemoryContextProperties tight = new MemoryContextProperties();
        tight.setDefaultWindowTokens(1024);
        tight.setReservedOutputTokens(700);
        tight.setSafetyReservePercent(0);
        MemoryContextBuilder builder = new MemoryContextBuilder(
                conversations,
                (id, owner) -> java.util.Optional.empty(),
                new ConservativeTokenEstimator(),
                tight);

        assertThatThrownBy(() -> builder.build(new MemoryContextRequest(
                conversationId,
                "owner",
                null,
                MemoryCallStage.DIRECT_ANSWER,
                "policy",
                "中".repeat(1000),
                1,
                Map.of(),
                List.of(),
                "")))
                .isInstanceOfSatisfying(MemoryContextException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MEMORY_CONTEXT_TOO_LARGE"));
    }

    @Test
    void currentTraceToolPairIsNeverPartiallyAdmitted() {
        MemoryContextBuilder builder = builder((id, owner) -> java.util.Optional.empty());

        assertThatThrownBy(() -> builder.build(new MemoryContextRequest(
                conversationId,
                "owner",
                null,
                MemoryCallStage.FINAL_SYNTHESIS,
                "policy",
                "current",
                1,
                Map.of(),
                List.of(new MemoryToolExchange("required", "中".repeat(5000), "result", true)),
                "")))
                .isInstanceOf(MemoryContextException.class);
    }

    @Test
    void trimmedSummaryDropsItsCoverageBoundaryAndFallsBackToRawHistory() {
        conversations.appendExchange(conversationId, "owner", "request-1", "turn-1", "raw-user", "raw-answer");
        ConversationMessage current = conversations.appendMessages(
                conversationId, "owner", List.of(MessageAppendCommand.text(
                        "turn-2", "request-2", 0, ConversationRole.USER, "current"))).get(0);
        MemoryContextProperties tight = new MemoryContextProperties();
        tight.setDefaultWindowTokens(1024);
        tight.setReservedOutputTokens(500);
        tight.setSafetyReservePercent(0);
        MemoryContextBuilder builder = new MemoryContextBuilder(
                conversations,
                (id, owner) -> java.util.Optional.of(new MemorySummaryView(2, "中".repeat(1000))),
                new ConservativeTokenEstimator(),
                tight);

        MemoryContext context = builder.build(new MemoryContextRequest(
                conversationId, "owner", null, MemoryCallStage.PLANNER, "policy", "current",
                current.sequence(), Map.of(), List.of(), ""));

        assertThat(context.summaryCoverageToSequence()).isZero();
        assertThat(context.trimmingReasons()).contains("SUMMARY_TRIMMED_BY_BUDGET");
        assertThat(context.recentMessages()).extracting(ConversationMessage::sequence).containsExactly(1L, 2L);
        assertThat(context.rendered()).contains("raw-user", "raw-answer");
    }

    @Test
    void requiredCurrentTraceToolsDisplaceSummaryAndRuntimeInsteadOfFailing() {
        MemoryContextProperties tight = new MemoryContextProperties();
        tight.setDefaultWindowTokens(1024);
        tight.setReservedOutputTokens(500);
        tight.setSafetyReservePercent(0);
        MemoryContextBuilder builder = new MemoryContextBuilder(
                conversations,
                (id, owner) -> java.util.Optional.of(new MemorySummaryView(0, "s".repeat(1400))),
                new ConservativeTokenEstimator(),
                tight);

        MemoryContext context = builder.build(new MemoryContextRequest(
                conversationId, "owner", null, MemoryCallStage.FINAL_SYNTHESIS, "policy", "current",
                1, Map.of("selectedText", "r".repeat(1400)),
                List.of(new MemoryToolExchange("required", "c".repeat(500), "o".repeat(500), true)), ""));

        assertThat(context.currentTraceTools()).singleElement()
                .extracting(MemoryToolExchange::toolCallId).isEqualTo("required");
        assertThat(context.rendered()).contains("c".repeat(500), "o".repeat(500));
        assertThat(context.trimmingReasons())
                .contains("RUNTIME_STATE_TRIMMED_BY_BUDGET", "SUMMARY_TRIMMED_BY_BUDGET");
        assertThat(context.estimatedTokens()).isLessThanOrEqualTo(context.inputBudgetTokens());
    }

    private MemoryContextBuilder builder(ConversationSummaryProvider summaries) {
        return new MemoryContextBuilder(
                conversations, summaries, new ConservativeTokenEstimator(), properties);
    }

    private MemoryContextRequest request(long currentSequence, List<MemoryToolExchange> tools) {
        return new MemoryContextRequest(
                conversationId,
                "owner",
                null,
                MemoryCallStage.PLANNER,
                "system policy",
                "unique-current-message",
                currentSequence,
                Map.of("documentId", "doc-1"),
                tools,
                "");
    }

    private static MessageAppendCommand tool(
            String turnId,
            String requestId,
            int ordinal,
            ConversationMessageType type,
            String content,
            String toolCallId
    ) {
        return new MessageAppendCommand(
                null, turnId, requestId, ordinal, ConversationRole.TOOL, type,
                content, "trace", toolCallId, Map.of("source", "agent"));
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
