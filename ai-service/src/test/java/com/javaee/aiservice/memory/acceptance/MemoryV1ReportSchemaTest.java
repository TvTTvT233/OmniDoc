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
    void reportSchemaDistinguishesAssetPendingStageAndFinalStatuses() throws Exception {
        JsonNode schema = loadSchema();

        assertThat(iterableText(schema.path("properties").path("status").path("enum")))
                .containsExactly(
                        "ASSET_VERIFIED", "PENDING_INTEGRATION", "PENDING_EXTERNAL",
                        "STAGE_VERIFIED", "AUTO_VERIFIED", "FAILED");
        assertThat(iterableText(schema.path("required"))).contains(
                "reportType", "stage", "status", "finalAcceptance", "manualStatus", "fixture",
                "environment", "boundaries", "tests", "gates", "contentSafety", "metrics",
                "stageGate", "unverified");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void stageReportContractCannotClaimFinalOrManualAcceptance() throws Exception {
        JsonNode schema = loadSchema();
        JsonNode stageThen = schema.path("allOf").path(0).path("then").path("properties");
        JsonNode stageGate = schema.path("$defs").path("stageGate");

        assertThat(iterableText(stageThen.path("status").path("enum")))
                .containsExactly("ASSET_VERIFIED", "PENDING_INTEGRATION", "STAGE_VERIFIED", "FAILED")
                .doesNotContain("AUTO_VERIFIED");
        assertThat(stageThen.path("finalAcceptance").path("const").asBoolean()).isFalse();
        assertThat(stageThen.path("manualStatus").path("const").asText()).isEqualTo("NOT_READY");
        assertThat(iterableText(stageThen.path("stage").path("enum")))
                .containsExactly("M02", "M03_A", "M03", "M06_A")
                .doesNotContain("M07");
        assertThat(iterableText(stageGate.path("required"))).containsExactlyInAnyOrder(
                "ready", "requiredTargets", "readinessMissing", "targetTestsExpected",
                "targetTestsSkipped", "regressionGroups");
    }

    @Test
    void autoVerifiedIsReservedForTheFinalM07Report() throws Exception {
        JsonNode schema = loadSchema();
        JsonNode autoThen = schema.path("allOf").path(1).path("then").path("properties");
        JsonNode finalThen = schema.path("allOf").path(2).path("then").path("properties");

        assertThat(autoThen.path("reportType").path("const").asText()).isEqualTo("FINAL_ACCEPTANCE");
        assertThat(autoThen.path("stage").path("const").asText()).isEqualTo("M07");
        assertThat(autoThen.path("finalAcceptance").path("const").asBoolean()).isTrue();
        assertThat(autoThen.path("manualStatus").path("const").asText()).isEqualTo("MANUAL_PENDING");
        assertThat(autoThen.path("stageGate").path("type").asText()).isEqualTo("null");
        assertThat(iterableText(finalThen.path("status").path("enum")))
                .containsExactly(
                        "ASSET_VERIFIED", "PENDING_INTEGRATION", "PENDING_EXTERNAL", "AUTO_VERIFIED", "FAILED")
                .doesNotContain("STAGE_VERIFIED");
        assertThat(finalThen.path("stage").path("const").asText()).isEqualTo("M07");
        assertThat(finalThen.path("stageGate").path("type").asText()).isEqualTo("null");
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
