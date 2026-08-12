package com.javaee.aiservice.review.m0.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0ReportSchemaTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void reportStatusSeparatesAssetsPendingIntegrationAndAutomaticVerification() throws Exception {
        JsonNode schema = resource("review-m0-acceptance-report.schema.json");
        assertThat(textValues(schema.path("properties").path("status").path("enum")))
                .containsExactly("ASSET_VERIFIED", "PENDING_INTEGRATION", "AUTO_VERIFIED");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(textValues(schema.path("required"))).contains(
                "schemaVersion", "contractVersion", "gateType", "mode", "status", "manualStatus",
                "finalAcceptance", "baselineCommit", "startedAt", "finishedAt", "counts", "p0",
                "external", "readinessMissing", "unverified");
        assertThat(schema.path("properties").path("schemaVersion").path("const").asText())
                .isEqualTo("REVIEW_M0_ACCEPTANCE_REPORT_V1");
        assertThat(schema.path("properties").path("contractVersion").path("const").asText())
                .isEqualTo("REVIEW_M0_CONTRACT_V1");
    }

    @Test
    void assetAndPendingStatusesCannotClaimFinalAcceptance() throws Exception {
        JsonNode conditions = resource("review-m0-acceptance-report.schema.json").path("allOf");
        JsonNode asset = conditions.path(0).path("then").path("properties");
        JsonNode pending = conditions.path(1).path("then").path("properties");
        assertThat(asset.path("mode").path("const").asText()).isEqualTo("ASSET");
        assertThat(asset.path("manualStatus").path("const").asText()).isEqualTo("NOT_READY");
        assertThat(asset.path("finalAcceptance").path("const").asBoolean()).isFalse();
        for (String field : List.of("failed", "errors", "skipped")) {
            assertThat(asset.path("counts").path("properties").path(field).path("const").asInt()).isZero();
            assertThat(pending.path("counts").path("properties").path(field).path("const").asInt()).isZero();
        }
        assertThat(pending.path("mode").path("const").asText()).isEqualTo("INTEGRATED");
        assertThat(pending.path("manualStatus").path("const").asText()).isEqualTo("NOT_READY");
        assertThat(pending.path("finalAcceptance").path("const").asBoolean()).isFalse();
        assertThat(pending.path("readinessMissing").path("minItems").asInt()).isOne();
    }

    @Test
    void autoVerifiedRequiresFinalAcceptanceZeroFailuresAndZeroP0Counts() throws Exception {
        JsonNode auto = resource("review-m0-acceptance-report.schema.json")
                .path("allOf").path(2).path("then").path("properties");
        assertThat(auto.path("mode").path("const").asText()).isEqualTo("INTEGRATED");
        assertThat(auto.path("manualStatus").path("const").asText()).isEqualTo("MANUAL_PENDING");
        assertThat(auto.path("finalAcceptance").path("const").asBoolean()).isTrue();
        for (String field : List.of("failed", "errors", "skipped")) {
            assertThat(auto.path("counts").path("properties").path(field).path("const").asInt()).isZero();
        }
        auto.path("p0").path("properties").fields().forEachRemaining(entry ->
                assertThat(entry.getValue().path("const").asInt()).isZero());
        assertThat(auto.path("readinessMissing").path("maxItems").asInt()).isZero();
    }

    @Test
    void reportDefinesEveryFrozenP0CounterAndExternalBoundary() throws Exception {
        JsonNode schema = resource("review-m0-acceptance-report.schema.json");
        assertThat(textValues(schema.path("properties").path("p0").path("required")))
                .containsExactlyInAnyOrder(
                        "missingBeforeUploadConfirmation", "deterministicFindingsWithoutRuleEvidence",
                        "deterministicFindingsWithoutMaterialEvidence", "systemErrorsReportedAsMissing",
                        "cannotConfirmReportedAsMissing", "originalFileMutations", "objectKeyMutations",
                        "remoteSendsWithoutMatchingConsent", "candidateChecklistsUsedForReview",
                        "nestedArchivesAccepted", "crossUserSuccessfulReads", "duplicatePublishedReports",
                        "staleReportsMarkedCurrent", "readyReportsWithIgnoredFindings",
                        "incrementalFullMismatches");
        assertThat(textValues(schema.path("properties").path("external").path("required")))
                .containsExactlyInAnyOrder("realMysql", "realFileService", "realDocumentService",
                        "realRemoteModel", "realBrowser");
        assertThat(fieldNames(schema.path("properties"))).doesNotContain(
                "original", "objectKey", "contentHash", "ocrText", "documentContent", "credential");
    }

    @Test
    void integratedEvidenceRequiresExactlyFifteenPassingTargetResults() throws Exception {
        JsonNode schema = resource("review-m0-integrated-evidence.schema.json");
        JsonNode results = schema.path("properties").path("targetResults");
        assertThat(results.path("minProperties").asInt()).isEqualTo(15);
        assertThat(results.path("maxProperties").asInt()).isEqualTo(15);
        assertThat(results.path("propertyNames").path("pattern").asText()).isEqualTo("^M0-P0-[0-9]{3}$");
        assertThat(results.path("additionalProperties").path("properties").path("status").path("const").asText())
                .isEqualTo("PASS");
        assertThat(results.path("additionalProperties").path("properties").path("assertions").path("minimum").asInt())
                .isOne();
        JsonNode p0 = schema.path("properties").path("p0");
        assertThat(p0.path("additionalProperties").asBoolean()).isFalse();
        p0.path("properties").fields().forEachRemaining(entry ->
                assertThat(entry.getValue().path("const").asInt()).isZero());
    }

    static JsonNode resource(String name) throws Exception {
        try (InputStream input = ReviewM0ReportSchemaTest.class.getResourceAsStream(
                "/review-m0/acceptance/" + name)) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }

    static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> values = new ArrayList<>();
        object.fieldNames().forEachRemaining(values::add);
        return values;
    }
}
