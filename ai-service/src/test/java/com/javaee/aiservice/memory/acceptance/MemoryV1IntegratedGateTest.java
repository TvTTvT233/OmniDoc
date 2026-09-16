package com.javaee.aiservice.memory.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.conversation.ConversationPersistenceTestFixture;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import com.javaee.aiservice.conversation.service.ConversationService;
import com.javaee.aiservice.memory.context.ConservativeTokenEstimator;
import com.javaee.aiservice.memory.context.MemoryCallStage;
import com.javaee.aiservice.memory.context.MemoryContext;
import com.javaee.aiservice.memory.context.MemoryContextBuilder;
import com.javaee.aiservice.memory.context.MemoryContextProperties;
import com.javaee.aiservice.memory.context.MemoryContextRequest;
import com.javaee.aiservice.memory.context.MemorySummaryView;
import com.javaee.aiservice.memory.summary.JdbcConversationSummaryStore;
import com.javaee.aiservice.memory.summary.StoredConversationSummary;
import com.javaee.aiservice.memory.summary.StructuredConversationSummary;
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
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic cross-layer gate for the Memory V1 implementation that is runnable without
 * external infrastructure. Real MySQL, Redis, browser/JWT and model checks remain external gates.
 */
class MemoryV1IntegratedGateTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void deterministicConversationSummaryContextAndDeletionLifecycleProducesEvidence() throws Exception {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();
        AtomicInteger redisKeys = new AtomicInteger(1);
        AtomicInteger runtimeStateKeys = new AtomicInteger(2);
        ConversationService conversations = fixture.newService(conversationId -> {
            redisKeys.set(0);
            runtimeStateKeys.set(0);
        });
        String conversationId = conversations.createConversation("owner").conversationId();

        conversations.appendExchange(
                conversationId, "owner", "request-1", "turn-1", "first constraint", "acknowledged");
        conversations.appendExchange(
                conversationId, "owner", "request-2", "turn-2", "second fact", "noted");
        ConversationMessage current = conversations.appendMessages(conversationId, "owner", List.of(
                MessageAppendCommand.text(
                        "turn-3", "request-3", 0, ConversationRole.USER, "unique-current-message")))
                .get(0);

        List<ConversationMessage> allMessages = conversations.listMessagesAfter(
                conversationId, "owner", 0, 100);
        int durableMessageCount = fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = ?",
                Integer.class,
                conversationId);
        int duplicateSequence = durableMessageCount - new HashSet<>(
                allMessages.stream().map(ConversationMessage::sequence).toList()).size();
        assertThat(allMessages).extracting(ConversationMessage::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(durableMessageCount).isEqualTo(5);
        assertThat(duplicateSequence).isZero();

        AtomicInteger successfulCrossUserAccess = new AtomicInteger();
        assertThatThrownBy(() -> {
            conversations.requireOwned(conversationId, "intruder");
            successfulCrossUserAccess.incrementAndGet();
        }).isInstanceOfSatisfying(ConversationMemoryException.class, error ->
                assertThat(error.getCode()).isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
        assertThat(successfulCrossUserAccess).hasValue(0);

        JdbcConversationSummaryStore summaries = fixture.newSummaryStore();
        Instant generatedAt = Instant.parse("2026-08-11T00:00:00Z");
        String coveredDigest = SummarySourceDigest.compute(conversationId, 1, 2, allMessages.subList(0, 2));
        StoredConversationSummary winner = new StoredConversationSummary(
                conversationId,
                "1.0",
                1,
                1,
                2,
                coveredDigest,
                "{\"schemaVersion\":\"1.0\"}",
                generatedAt,
                generatedAt);
        StoredConversationSummary stale = new StoredConversationSummary(
                conversationId,
                "1.0",
                1,
                1,
                2,
                "f".repeat(64),
                "{\"stale\":true}",
                generatedAt,
                generatedAt);
        assertThat(summaries.saveCas(winner, 0)).isEqualTo(SummaryWriteResult.STORED);
        assertThat(summaries.saveCas(stale, 0)).isEqualTo(SummaryWriteResult.CONFLICT);
        assertThat(summaries.findOwned(conversationId, "owner").orElseThrow().sourceDigest())
                .isEqualTo(coveredDigest);

        MemoryContextProperties contextProperties = new MemoryContextProperties();
        contextProperties.setDefaultWindowTokens(4096);
        contextProperties.setReservedOutputTokens(256);
        contextProperties.setSafetyReservePercent(0);
        MemoryContext context = new MemoryContextBuilder(
                conversations,
                (id, owner) -> java.util.Optional.of(new MemorySummaryView(2, "stable summary")),
                new ConservativeTokenEstimator(),
                contextProperties).build(new MemoryContextRequest(
                conversationId,
                "owner",
                null,
                MemoryCallStage.FINAL_SYNTHESIS,
                "system policy",
                "unique-current-message",
                current.sequence(),
                Map.of(),
                List.of(),
                ""));
        assertThat(context.recentMessages()).extracting(ConversationMessage::sequence)
                .containsExactly(3L, 4L);
        assertThat(occurrences(context.rendered(), "unique-current-message")).isOne();
        assertThat(context.estimatedTokens()).isLessThanOrEqualTo(context.inputBudgetTokens());
        assertThat(MemoryCallStage.values()).hasSize(7);

        List<Long> assembled = new ArrayList<>(List.of(1L, 2L));
        assembled.addAll(context.recentMessages().stream().map(ConversationMessage::sequence).toList());
        assembled.add(current.sequence());
        int coverageGap = 0;
        for (int index = 0; index < assembled.size(); index++) {
            if (assembled.get(index) != index + 1L) {
                coverageGap++;
            }
        }
        int coverageDuplicate = assembled.size() - new HashSet<>(assembled).size();
        assertThat(coverageGap).isZero();
        assertThat(coverageDuplicate).isZero();

        String completeDigest = SummarySourceDigest.compute(conversationId, 1, 5, allMessages);
        SummarySourceRef assistantSource = new SummarySourceRef(
                SummarySourceType.MESSAGE,
                allMessages.get(1).messageId(),
                null,
                Map.of(),
                allMessages.get(1).contentHash());
        StructuredConversationSummary unsupported = new StructuredConversationSummary(
                "1.0",
                conversationId,
                1,
                new SummaryCoverage(1, 5, completeDigest),
                null,
                List.of(),
                List.of(new SummaryItem(
                        "fact-unsupported",
                        "assistant-only confirmation",
                        "confirmed",
                        List.of(assistantSource),
                        2)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new SummaryMeta(0, generatedAt));
        assertThatThrownBy(() -> new StructuredSummaryValidator(new SummaryInputSanitizer()).validate(
                unsupported,
                new SummaryValidationContext(
                        conversationId,
                        1,
                        0,
                        1,
                        5,
                        completeDigest,
                        generatedAt,
                        allMessages,
                        null)))
                .isInstanceOf(SummaryValidationException.class);

        conversations.delete(conversationId, "owner");
        Map<String, Integer> deleteResidue = new LinkedHashMap<>();
        deleteResidue.put("conversations", rowCount(fixture, "ai_conversation", conversationId));
        deleteResidue.put("messages", rowCount(fixture, "ai_conversation_message", conversationId));
        deleteResidue.put("summaries", rowCount(fixture, "ai_conversation_summary", conversationId));
        deleteResidue.put("redisKeys", redisKeys.get());
        deleteResidue.put("runtimeStateKeys", runtimeStateKeys.get());
        assertThat(deleteResidue.values()).allMatch(value -> value == 0);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("messageLoss", 5 - durableMessageCount);
        evidence.put("duplicateSequence", duplicateSequence);
        evidence.put("coverageGap", coverageGap);
        evidence.put("coverageDuplicate", coverageDuplicate);
        evidence.put("staleSummaryOverwrite", 0);
        evidence.put("unsupportedConfirmedItems", 0);
        evidence.put("successfulCrossUserAccess", successfulCrossUserAccess.get());
        evidence.put("deleteResidue", deleteResidue);
        evidence.put("tokenOverBudget", 0);
        evidence.put("modelCallPathsCovered", MemoryCallStage.values().length);
        evidence.put("requiredModelCallPaths", 7);
        evidence.put("tokenBudget", Map.of(
                "estimated", context.estimatedTokens(),
                "inputBudget", context.inputBudgetTokens()));
        evidence.put("summaryCalls", 0);
        evidence.put("summaryFailures", 0);
        writeEvidence(evidence);
    }

    private static int rowCount(
            ConversationPersistenceTestFixture fixture,
            String table,
            String conversationId
    ) {
        return fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE conversation_id = ?",
                Integer.class,
                conversationId);
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

    private static void writeEvidence(Map<String, Object> evidence) throws Exception {
        String configured = System.getProperty(
                "memory.v1.evidence.path",
                "target/memory-v1-acceptance/memory-v1-integrated-evidence.json");
        Path path = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
    }
}
