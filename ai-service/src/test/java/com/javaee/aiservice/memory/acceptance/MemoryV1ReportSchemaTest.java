package com.javaee.aiservice.memory.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryV1ReportSchemaTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void reportSchemaDistinguishesAssetPendingAndFinalStatuses() throws Exception {
        JsonNode schema = loadSchema();

        assertThat(iterableText(schema.path("properties").path("status").path("enum")))
                .containsExactly("ASSET_VERIFIED", "PENDING_INTEGRATION", "AUTO_VERIFIED", "FAILED");
        assertThat(iterableText(schema.path("required"))).contains(
                "status", "manualStatus", "fixture", "environment", "boundaries",
                "tests", "gates", "contentSafety", "metrics", "unverified");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void reportSchemaRequiresAllFrozenP0GateEntryPoints() throws Exception {
        JsonNode gates = loadSchema().path("properties").path("gates");

        assertThat(iterableText(gates.path("required"))).containsExactlyInAnyOrder(
                "messageLoss", "duplicateSequence", "coverageGap", "coverageDuplicate",
                "staleSummaryOverwrite", "unsupportedConfirmedItems", "successfulCrossUserAccess",
                "deleteResidue", "tokenOverBudget", "modelCallPathsCovered", "requiredModelCallPaths");
        assertThat(gates.path("properties").path("requiredModelCallPaths").path("const").asInt())
                .isEqualTo(7);
        assertThat(iterableText(gates.path("properties").path("deleteResidue").path("required")))
                .containsExactlyInAnyOrder(
                        "conversations", "messages", "summaries", "redisKeys", "runtimeStateKeys");
    }

    @Test
    void reportSchemaForbidsRawContentPayloadsAndRequiresFalseSafetyFlags() throws Exception {
        JsonNode schema = loadSchema();
        JsonNode topLevelProperties = schema.path("properties");
        JsonNode safety = topLevelProperties.path("contentSafety");

        assertThat(iterableFieldNames(topLevelProperties)).doesNotContain(
                "messages", "conversation", "summary", "prompt", "document", "toolResults", "selectedText");
        assertThat(iterableText(safety.path("required"))).containsExactlyInAnyOrder(
                "containsConversationContent", "containsSummaryContent", "containsDocumentContent",
                "containsPromptContent", "containsSelectedText", "containsToolResultContent", "containsCredential");
        safety.path("properties").fields().forEachRemaining(entry ->
                assertThat(entry.getValue().path("const").asBoolean()).isFalse());
    }

    private static JsonNode loadSchema() throws Exception {
        try (InputStream input = MemoryV1ReportSchemaTest.class
                .getResourceAsStream("/memory/memory-v1-report-schema.json")) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static List<String> iterableText(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.elements().forEachRemaining(item -> values.add(item.asText()));
        return values;
    }

    private static List<String> iterableFieldNames(JsonNode object) {
        List<String> values = new ArrayList<>();
        object.fieldNames().forEachRemaining(values::add);
        return values;
    }
}
