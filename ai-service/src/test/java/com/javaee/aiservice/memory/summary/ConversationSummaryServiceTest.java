package com.javaee.aiservice.memory.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.conversation.ConversationPersistenceTestFixture;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.conversation.service.ConversationService;
import com.javaee.aiservice.memory.context.ConservativeTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationSummaryServiceTest {

    private ConversationPersistenceTestFixture fixture;
    private ConversationService conversations;
    private JdbcConversationSummaryStore store;
    private StructuredSummaryCodec codec;
    private SummaryInputSanitizer sanitizer;
    private StructuredSummaryValidator validator;
    private SummaryModelClient modelClient;
    private SummaryProperties properties;

    @BeforeEach
    void setUp() {
        fixture = new ConversationPersistenceTestFixture();
        conversations = fixture.newService();
        store = fixture.newSummaryStore();
        codec = new StructuredSummaryCodec(new ObjectMapper());
        sanitizer = new SummaryInputSanitizer();
        validator = new StructuredSummaryValidator(sanitizer);
        modelClient = mock(SummaryModelClient.class);
        properties = new SummaryProperties();
        properties.setTriggerInputTokens(256);
        properties.setRetainRecentTokens(0);
        properties.setMaxSourceTokens(4096);
        properties.setMaxAttempts(1);
    }

    @Test
    void tokenThresholdCreatesAValidatedSummaryAndProviderRendersItSeparatelyFromJson() {
        String conversationId = conversations.createConversation("owner").conversationId();
        conversations.appendExchange(conversationId, "owner", "request-1", "turn-1",
                "remember-this-goal ".repeat(40), "acknowledged ".repeat(40));
        List<ConversationMessage> messages = conversations.listMessagesAfter(conversationId, "owner", 0, 200);
        String digest = SummarySourceDigest.compute(conversationId, 1, 2, messages);
        StructuredConversationSummary candidate = summary(
                conversationId, 1, 0, 2, digest,
                new SummaryItem("goal-1", "remember this goal", "active",
                        List.of(messageRef(messages.get(0))), 1),
                List.of(), List.of(), List.of());
        when(modelClient.generate(any(), any(), any())).thenAnswer(call -> codec.write(
                withGeneratedAt(candidate, generatedAtFromPrompt(call.getArgument(0)))));

        ConversationSummaryService service = newService();
        SummaryUpdateResult result = service.maybeUpdate(conversationId, "owner", null);

        assertThat(result.status()).isEqualTo(SummaryUpdateStatus.UPDATED);
        assertThat(result.coverageToSequence()).isEqualTo(2);
        StoredConversationSummary stored = store.findOwned(conversationId, "owner").orElseThrow();
        assertThat(stored.sourceDigest()).isEqualTo(digest);
        assertThat(service.findOwned(conversationId, "owner").orElseThrow().renderedText())
                .contains("GOAL:", "remember this goal")
                .doesNotContain("\"schemaVersion\"");
    }

    @Test
    void sourceDigestMatchesTheFrozenCanonicalFixture() {
        List<ConversationMessage> messages = List.of(
                fixtureMessage("memory-v1-msg-001", 1, ConversationRole.USER,
                        "fde557b6a84960c94fbabe011c573337dca49051f600c428ca34a15a35e367ae"),
                fixtureMessage("memory-v1-msg-002", 2, ConversationRole.ASSISTANT,
                        "103fe44bc308bbc604781951c362db7ccc7c80cc70725e01b5ac83a981a573e0"),
                fixtureMessage("memory-v1-msg-003", 3, ConversationRole.USER,
                        "e29e97750a897c934b100281633a4adffb8ed4751efd24f02f65f2287c7ea7d0"),
                fixtureMessage("memory-v1-msg-004", 4, ConversationRole.ASSISTANT,
                        "a02226a025dd8c2caab0be0b7b1b37cc0199856237bc9cdfdf83dcd13cb68cea"),
                fixtureMessage("memory-v1-msg-005", 5, ConversationRole.USER,
                        "0a2f709aeb8088cedbcd3d8e4186b4815802cf5bcf029a0911eb4061f5fd834c"));

        assertThat(SummarySourceDigest.compute("memory-v1-conv-a", 1, 5, messages))
                .isEqualTo("ddce4c5c3ef38736ab417fa75345e031641dff458bee1a210f5acd34839e7ae8");
    }

    @Test
    void modelOrSchemaFailureKeepsThePreviousSummaryAndCoverage() {
        String conversationId = conversations.createConversation("owner").conversationId();
        conversations.appendExchange(conversationId, "owner", "request-1", "turn-1",
                "first ".repeat(120), "answer ".repeat(120));
        List<ConversationMessage> firstMessages = conversations.listMessagesAfter(conversationId, "owner", 0, 200);
        String firstDigest = SummarySourceDigest.compute(conversationId, 1, 2, firstMessages);
        StructuredConversationSummary first = summary(
                conversationId, 1, 0, 2, firstDigest, null, List.of(), List.of(), List.of());
        AtomicInteger calls = new AtomicInteger();
        when(modelClient.generate(any(), any(), any())).thenAnswer(call -> calls.getAndIncrement() == 0
                ? codec.write(withGeneratedAt(first, generatedAtFromPrompt(call.getArgument(0))))
                : "{not-valid-summary}");
        ConversationSummaryService service = newService();
        assertThat(service.maybeUpdate(conversationId, "owner", null).status())
                .isEqualTo(SummaryUpdateStatus.UPDATED);

        conversations.appendExchange(conversationId, "owner", "request-2", "turn-2",
                "second ".repeat(120), "answer-two ".repeat(120));
        SummaryUpdateResult failed = service.maybeUpdate(conversationId, "owner", null);

        assertThat(failed.status()).isEqualTo(SummaryUpdateStatus.FAILED);
        StoredConversationSummary retained = store.findOwned(conversationId, "owner").orElseThrow();
        assertThat(retained.summaryVersion()).isEqualTo(1);
        assertThat(retained.toSequence()).isEqualTo(2);
        assertThat(retained.sourceDigest()).isEqualTo(firstDigest);
    }

    @Test
    void staleCasCannotOverwriteAndExactRetryIsIdempotent() {
        String conversationId = conversations.createConversation("owner").conversationId();
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        StoredConversationSummary first = new StoredConversationSummary(
                conversationId, "1.0", 1, 1, 1, "a".repeat(64), "{\"one\":true}", now, now);
        StoredConversationSummary staleDifferent = new StoredConversationSummary(
                conversationId, "1.0", 1, 1, 1, "b".repeat(64), "{\"stale\":true}", now, now);

        assertThat(store.saveCas(first, 0)).isEqualTo(SummaryWriteResult.STORED);
        assertThat(store.saveCas(first, 0)).isEqualTo(SummaryWriteResult.IDEMPOTENT);
        assertThat(store.saveCas(staleDifferent, 0)).isEqualTo(SummaryWriteResult.CONFLICT);
        assertThat(store.findOwned(conversationId, "owner").orElseThrow().summaryJson())
                .isEqualTo("{\"one\":true}");
    }

    @Test
    void assistantOnlyEvidenceCannotConfirmFactsOrDecisions() {
        String conversationId = conversations.createConversation("owner").conversationId();
        List<ConversationMessage> messages = conversations.appendExchange(
                conversationId, "owner", "request-1", "turn-1", "Please advise", "I suggest option A");
        String digest = SummarySourceDigest.compute(conversationId, 1, 2, messages);
        SummaryItem assistantFact = new SummaryItem(
                "fact-1", "Option A is a fact", "confirmed",
                List.of(messageRef(messages.get(1))), 2);
        SummaryItem assistantDecision = new SummaryItem(
                "decision-1", "Use option A", "confirmed",
                List.of(messageRef(messages.get(1))), 2);
        StructuredConversationSummary candidate = summary(
                conversationId, 1, 0, 2, digest, null,
                List.of(assistantFact), List.of(assistantDecision), List.of());

        assertThatThrownBy(() -> validator.validate(candidate, new SummaryValidationContext(
                conversationId, 1, 0, 1, 2, digest,
                Instant.parse("2026-08-11T00:00:00Z"), messages, null)))
                .isInstanceOf(SummaryValidationException.class);
    }

    @Test
    void promptRedactsCredentialsAndOmitsToolBodies() {
        String conversationId = conversations.createConversation("owner").conversationId();
        ConversationMessage user = conversations.appendMessages(conversationId, "owner", List.of(
                MessageAppendCommand.text("turn-1", "request-1", 0, ConversationRole.USER,
                        "api_key=do-not-copy remember the deadline"))).get(0);
        SummaryPromptBuilder promptBuilder = new SummaryPromptBuilder(codec, sanitizer);

        String prompt = promptBuilder.build(null, List.of(user), conversationId, 1, 0, 1,
                SummarySourceDigest.compute(conversationId, 1, 1, List.of(user)),
                Instant.parse("2026-08-11T00:00:00Z"));

        assertThat(prompt).contains("[REDACTED_SENSITIVE_VALUE]").doesNotContain("do-not-copy");
    }

    @Test
    void codecRejectsMissingRequiredArraysInsteadOfDefaultingThemToEmpty() {
        assertThatThrownBy(() -> codec.parse("""
                {
                  "schemaVersion":"1.0","conversationId":"conv","summaryVersion":1,
                  "coverage":{"fromSeq":1,"toSeq":1,"sourceDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                  "goal":null,
                  "meta":{"previousSummaryVersion":0,"generatedAt":"2026-08-11T00:00:00Z"}
                }
                """))
                .isInstanceOf(SummaryValidationException.class);
    }

    @Test
    void rollingUpdateUsesThePreviousSummaryAndOnlyTheNewContiguousDelta() {
        String conversationId = conversations.createConversation("owner").conversationId();
        conversations.appendExchange(conversationId, "owner", "request-1", "turn-1",
                "raw-first-user-only ".repeat(40), "raw-first-assistant-only ".repeat(40));
        List<ConversationMessage> firstMessages = conversations.listMessagesAfter(
                conversationId, "owner", 0, 200);
        SummaryItem goal = new SummaryItem("goal-1", "keep the stable goal", "active",
                List.of(messageRef(firstMessages.get(0))), 1);
        StructuredConversationSummary first = summary(
                conversationId, 1, 0, 2,
                SummarySourceDigest.compute(conversationId, 1, 2, firstMessages),
                goal, List.of(), List.of(), List.of());
        AtomicInteger calls = new AtomicInteger();
        when(modelClient.generate(any(), any(), any())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                return codec.write(withGeneratedAt(first, generatedAtFromPrompt(prompt)));
            }
            List<ConversationMessage> allMessages = conversations.listMessagesAfter(
                    conversationId, "owner", 0, 200);
            assertThat(prompt)
                    .contains("<PREVIOUS_SUMMARY_UNTRUSTED_DATA>", "\"summaryVersion\":1", "[sequence=3", "[sequence=4")
                    .doesNotContain("raw-first-user-only", "raw-first-assistant-only");
            StructuredConversationSummary second = summary(
                    conversationId, 2, 1, 4,
                    SummarySourceDigest.compute(conversationId, 1, 4, allMessages),
                    goal, List.of(), List.of(), List.of());
            return codec.write(withGeneratedAt(second, generatedAtFromPrompt(prompt)));
        });
        ConversationSummaryService service = newService();

        assertThat(service.maybeUpdate(conversationId, "owner", null).status())
                .isEqualTo(SummaryUpdateStatus.UPDATED);
        conversations.appendExchange(conversationId, "owner", "request-2", "turn-2",
                "raw-second-user ".repeat(40), "raw-second-assistant ".repeat(40));
        SummaryUpdateResult secondUpdate = service.maybeUpdate(conversationId, "owner", null);

        assertThat(secondUpdate.status()).isEqualTo(SummaryUpdateStatus.UPDATED);
        assertThat(secondUpdate.coverageToSequence()).isEqualTo(4);
        assertThat(store.findOwned(conversationId, "owner").orElseThrow().summaryVersion()).isEqualTo(2);
    }

    @Test
    void aSingleOversizedSourceIsNeverSilentlySentOrTruncated() {
        String conversationId = conversations.createConversation("owner").conversationId();
        conversations.appendExchange(conversationId, "owner", "request-1", "turn-1",
                "oversized-source ".repeat(200), "acknowledged");
        properties.setMaxSourceTokens(256);

        SummaryUpdateResult result = newService().maybeUpdate(conversationId, "owner", null);

        assertThat(result.status()).isEqualTo(SummaryUpdateStatus.SOURCE_TOO_LARGE);
        assertThat(result.coverageToSequence()).isZero();
        assertThat(store.findOwned(conversationId, "owner")).isEmpty();
        verifyNoInteractions(modelClient);
    }

    @Test
    void permanentConversationDeletionCascadesToTheDerivedSummary() {
        String conversationId = conversations.createConversation("owner").conversationId();
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        StoredConversationSummary stored = new StoredConversationSummary(
                conversationId, "1.0", 1, 1, 1, "a".repeat(64), "{\"fixture\":true}", now, now);
        assertThat(store.saveCas(stored, 0)).isEqualTo(SummaryWriteResult.STORED);

        conversations.delete(conversationId, "owner");

        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation_summary WHERE conversation_id = ?",
                Integer.class,
                conversationId)).isZero();
    }

    private ConversationSummaryService newService() {
        StructuredSummaryRenderer renderer = new StructuredSummaryRenderer();
        return new ConversationSummaryService(
                conversations,
                store,
                codec,
                validator,
                renderer,
                new SummaryPromptBuilder(codec, sanitizer),
                modelClient,
                properties,
                fixture.properties(),
                new ConservativeTokenEstimator());
    }

    static StructuredConversationSummary summary(
            String conversationId,
            long version,
            long previousVersion,
            long toSequence,
            String digest,
            SummaryItem goal,
            List<SummaryItem> facts,
            List<SummaryItem> decisions,
            List<SummaryItem> todos
    ) {
        return new StructuredConversationSummary(
                "1.0", conversationId, version, new SummaryCoverage(1, toSequence, digest),
                goal, List.of(), facts, decisions, List.of(), todos, List.of(),
                new SummaryMeta(previousVersion, Instant.parse("2026-08-11T00:00:00Z")));
    }

    static SummarySourceRef messageRef(ConversationMessage message) {
        return new SummarySourceRef(
                SummarySourceType.MESSAGE, message.messageId(), null, Map.of(), message.contentHash());
    }

    private static StructuredConversationSummary withGeneratedAt(
            StructuredConversationSummary summary,
            Instant generatedAt
    ) {
        return new StructuredConversationSummary(
                summary.schemaVersion(), summary.conversationId(), summary.summaryVersion(), summary.coverage(),
                summary.goal(), summary.constraints(), summary.facts(), summary.decisions(),
                summary.openQuestions(), summary.todos(), summary.resources(),
                new SummaryMeta(summary.meta().previousSummaryVersion(), generatedAt));
    }

    private static Instant generatedAtFromPrompt(String prompt) {
        String marker = "meta.generatedAt=";
        int start = prompt.indexOf(marker);
        int end = prompt.indexOf('\n', start);
        return Instant.parse(prompt.substring(start + marker.length(), end).trim());
    }

    private static ConversationMessage fixtureMessage(
            String messageId,
            long sequence,
            ConversationRole role,
            String contentHash
    ) {
        return new ConversationMessage(
                messageId, "memory-v1-conv-a", sequence, "turn", "request", 0,
                role, ConversationMessageType.TEXT, "fixture", null, null, Map.of(),
                contentHash, Instant.EPOCH);
    }
}
