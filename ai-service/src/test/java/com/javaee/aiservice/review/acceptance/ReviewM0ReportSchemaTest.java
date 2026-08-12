package com.javaee.aiservice.review.acceptance;

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
                .containsExactly("ASSET_VERIFIED", "PENDING_INTEGRATION", "AUTO_VERIFIED", "FAILED");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(textValues(schema.path("required"))).contains(
                "mode", "status", "productionVerified", "selector", "tests", "coverage",
                "gates", "contentSafety", "unverified");
    }

    @Test
    void assetAndPendingStatusesCannotClaimProductionVerification() throws Exception {
        JsonNode conditions = resource("review-m0-acceptance-report.schema.json").path("allOf");
        JsonNode asset = conditions.path(0).path("then").path("properties");
        JsonNode pending = conditions.path(1).path("then").path("properties");
        JsonNode nonFinal = conditions.path(3).path("then").path("properties");
        assertThat(asset.path("mode").path("const").asText()).isEqualTo("ASSET");
        assertThat(asset.path("productionVerified").path("const").asBoolean()).isFalse();
        for (String field : List.of("failures", "errors", "skipped")) {
            assertThat(asset.path("tests").path("properties").path(field).path("const").asInt()).isZero();
            assertThat(pending.path("tests").path("properties").path(field).path("const").asInt()).isZero();
        }
        assertThat(asset.path("tests").path("properties").path("integrated").path("type").asText())
                .isEqualTo("null");
        assertThat(pending.path("mode").path("const").asText()).isEqualTo("INTEGRATED");
        assertThat(pending.path("productionVerified").path("const").asBoolean()).isFalse();
        assertThat(pending.path("tests").path("properties").path("integrated").path("type").asText())
                .isEqualTo("null");
        assertThat(nonFinal.path("productionVerified").path("const").asBoolean()).isFalse();
    }

    @Test
    void autoVerifiedRequiresReadySelectorAllTargetsZeroFailuresAndZeroGates() throws Exception {
        JsonNode auto = resource("review-m0-acceptance-report.schema.json")
                .path("allOf").path(2).path("then").path("properties");
        assertThat(auto.path("mode").path("const").asText()).isEqualTo("INTEGRATED");
        assertThat(auto.path("productionVerified").path("const").asBoolean()).isTrue();
        assertThat(auto.path("selector").path("properties").path("state").path("const").asText())
                .isEqualTo("READY");
        assertThat(auto.path("coverage").path("properties").path("integratedTargetsPassed").path("const").asInt())
                .isEqualTo(15);
        for (String field : List.of("failures", "errors", "skipped")) {
            assertThat(auto.path("tests").path("properties").path(field).path("const").asInt()).isZero();
        }
        auto.path("gates").path("properties").fields().forEachRemaining(entry ->
                assertThat(entry.getValue().path("const").asInt()).isZero());
        assertThat(auto.path("unverified").path("maxItems").asInt()).isZero();
    }

    @Test
    void reportDefinesEveryP0NegativeGateAndForbidsPayloadContent() throws Exception {
        JsonNode schema = resource("review-m0-acceptance-report.schema.json");
        assertThat(textValues(schema.path("properties").path("gates").path("required")))
                .containsExactlyInAnyOrder(
                        "inventoryUnconfirmedReviewAttempts", "unanchoredOfficialFindings",
                        "userRulesClaimedOfficial", "missingBeforeUploadConfirmation",
                        "unreadableClassifiedMissing", "ambiguousNamingDefinitiveFindings",
                        "identityMutations", "ignoredReadyTransitions", "idempotencyDuplicates",
                        "recoveryDuplicates", "staleSnapshotsNotOutdated", "successfulCrossUserAccess",
                        "sensitivePayloadLeaks", "forbiddenM0Conclusions", "formalFormatFailures",
                        "unsafeZipAccepted");
        JsonNode safety = schema.path("properties").path("contentSafety");
        assertThat(textValues(safety.path("required"))).containsExactlyInAnyOrder(
                "containsDocumentContent", "containsOcrText", "containsObjectKey",
                "containsPersonalIdentifier", "containsCredential");
        safety.path("properties").fields().forEachRemaining(entry ->
                assertThat(entry.getValue().path("const").asBoolean()).isFalse());
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
    }

    static JsonNode resource(String name) throws Exception {
        try (InputStream input = ReviewM0ReportSchemaTest.class.getResourceAsStream(
                "/review/m0/acceptance/" + name)) {
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
