package com.javaee.aiservice.memory.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryV1FixtureTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void fixtureIsSyntheticLocalAndConversationOnly() throws Exception {
        JsonNode fixture = loadFixture();

        assertThat(fixture.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(fixture.path("id").asText()).isEqualTo("memory-v1-synthetic-conversation-v1");
        assertThat(fixture.path("synthetic").asBoolean()).isTrue();
        assertThat(fixture.path("remoteModel").asBoolean()).isFalse();
        assertThat(fixture.path("remoteEmbedding").asBoolean()).isFalse();
        assertThat(fixture.path("conversation").path("conversationId").asText())
                .startsWith("memory-v1-conv-");
        assertThat(fixture.path("conversation").path("userId").asText())
                .startsWith("memory-v1-user-");
    }

    @Test
    void messagesHaveContiguousSequencesUniqueIdsAndDeterministicHashes() throws Exception {
        JsonNode messages = loadFixture().path("messages");
        List<Integer> sequences = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        for (JsonNode message : messages) {
            sequences.add(message.path("sequence").asInt());
            ids.add(message.path("messageId").asText());
            assertThat(message.path("contentHash").asText())
                    .isEqualTo(sha256(message.path("content").asText()));
        }

        assertThat(sequences).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(ids).hasSize(messages.size());
    }

    @Test
    void sourceDigestMatchesTheFrozenCanonicalForm() throws Exception {
        JsonNode fixture = loadFixture();
        JsonNode summary = fixture.path("expectedSummary");
        int fromSeq = summary.path("coverage").path("fromSeq").asInt();
        int toSeq = summary.path("coverage").path("toSeq").asInt();
        StringBuilder canonical = new StringBuilder()
                .append(fixture.path("conversation").path("conversationId").asText()).append('\n')
                .append(fromSeq).append('\n')
                .append(toSeq).append('\n');

        for (JsonNode message : fixture.path("messages")) {
            int sequence = message.path("sequence").asInt();
            if (sequence < fromSeq || sequence > toSeq) {
                continue;
            }
            canonical.append(sequence).append('|')
                    .append(message.path("messageId").asText()).append('|')
                    .append(message.path("role").asText()).append('|')
                    .append(message.path("messageType").asText()).append('|')
                    .append(message.path("contentHash").asText()).append('\n');
        }

        assertThat(sha256(canonical.toString()))
                .isEqualTo(summary.path("coverage").path("sourceDigest").asText());
    }

    @Test
    void summaryRawWindowAndCurrentMessagePartitionWithoutGapOrDuplicate() throws Exception {
        JsonNode expectations = loadFixture().path("expectations");
        List<Integer> assembled = new ArrayList<>();
        iterableInts(expectations.path("summarySequences")).forEach(assembled::add);
        iterableInts(expectations.path("recentRawSequences")).forEach(assembled::add);
        assembled.add(expectations.path("currentMessageSequence").asInt());

        assertThat(assembled).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(new HashSet<>(assembled)).hasSameSizeAs(assembled);
    }

    @Test
    void fixturePreservesToolCallResultPairAndFactSourceRules() throws Exception {
        JsonNode fixture = loadFixture();
        Map<String, JsonNode> messagesById = new HashMap<>();
        for (JsonNode message : fixture.path("messages")) {
            messagesById.put(message.path("messageId").asText(), message);
        }

        JsonNode pair = fixture.path("expectations").path("toolPairs").get(0);
        JsonNode call = messageAtSequence(fixture.path("messages"), pair.path("callSequence").asInt());
        JsonNode result = messageAtSequence(fixture.path("messages"), pair.path("resultSequence").asInt());
        assertThat(call.path("messageType").asText()).isEqualTo("TOOL_CALL");
        assertThat(result.path("messageType").asText()).isEqualTo("TOOL_RESULT");
        assertThat(call.path("toolCallId").asText()).isEqualTo(pair.path("toolCallId").asText());
        assertThat(result.path("toolCallId").asText()).isEqualTo(pair.path("toolCallId").asText());

        for (JsonNode fact : fixture.path("expectedSummary").path("facts")) {
            if (!"confirmed".equals(fact.path("status").asText())) {
                continue;
            }
            for (JsonNode source : fact.path("sourceRefs")) {
                if ("MESSAGE".equals(source.path("type").asText())) {
                    assertThat(messagesById.get(source.path("id").asText()).path("role").asText())
                            .isEqualTo("USER");
                }
            }
        }
        assertThat(fixture.path("expectedSummary").path("decisions").get(0).path("status").asText())
                .isEqualTo("proposed");
    }

    @Test
    void runtimeValuesAreAbsentFromTheLongTermSummary() throws Exception {
        JsonNode fixture = loadFixture();
        String summary = OBJECT_MAPPER.writeValueAsString(fixture.path("expectedSummary"));

        fixture.path("runtimeState").fields().forEachRemaining(entry ->
                assertThat(summary).doesNotContain(entry.getValue().asText()));
        assertThat(fixture.path("expectations").path("successfulCrossUserOperations").asInt()).isZero();
        fixture.path("expectations").path("deletionResidue").fields().forEachRemaining(entry ->
                assertThat(entry.getValue().asInt()).isZero());
    }

    private static JsonNode loadFixture() throws Exception {
        try (InputStream input = MemoryV1FixtureTest.class
                .getResourceAsStream("/memory/memory-v1-fixture.json")) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static JsonNode messageAtSequence(JsonNode messages, int sequence) {
        for (JsonNode message : messages) {
            if (message.path("sequence").asInt() == sequence) {
                return message;
            }
        }
        throw new AssertionError("Missing fixture message sequence " + sequence);
    }

    private static List<Integer> iterableInts(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        array.elements().forEachRemaining(item -> values.add(item.asInt()));
        return values;
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            hex.append(String.format("%02x", item));
        }
        return hex.toString();
    }
}
