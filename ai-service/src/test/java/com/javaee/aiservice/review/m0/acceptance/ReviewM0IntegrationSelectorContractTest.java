package com.javaee.aiservice.review.m0.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0IntegrationSelectorContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void selectorDeclaresCompleteProductionAdapterEvenWhileIntegrationDependenciesArePending() throws Exception {
        JsonNode selector = selector();
        assertThat(selector.path("state").asText()).isIn("PENDING_INTEGRATION", "READY");
        assertThat(selector.path("adapterId").asText())
                .isEqualTo("review-m0-r1-d-production-behavior-v1");
        assertThat(selector.path("testClasses")).hasSize(1);
        assertThat(selector.path("staticScanRoots")).hasSizeGreaterThanOrEqualTo(2);
        List<String> targets = new ArrayList<>();
        selector.path("targetSelectors").fieldNames().forEachRemaining(targets::add);
        targets.sort(String::compareTo);
        assertThat(targets).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(value -> "M0-P0-%03d".formatted(value))
                .toList());
        selector.path("targetSelectors").forEach(target -> {
            assertThat(target.path("className").asText())
                    .isEqualTo("com.javaee.aiservice.review.m0.acceptance.ReviewM0IntegratedEvidenceAdapter");
            assertThat(target.path("methodName").asText()).startsWith("m0P0_");
        });
        if ("PENDING_INTEGRATION".equals(selector.path("state").asText())) {
            assertThat(selector.path("pendingReasons")).hasSize(3);
            assertThat(selector.path("pendingReasons").toString())
                    .contains("IMMUTABLE_FIRST_RESPONSE_REPLAY", "CANCEL_REQUESTED_RESTART_CONVERGENCE",
                            "OWNER_AWARE_FILE_SERVICE_AND_MINIO_CHAIN");
        } else {
            assertThat(selector.path("pendingReasons")).isEmpty();
        }
    }

    @Test
    void selectorSchemaMakesReadyStateAnExplicitAdapterBoundary() throws Exception {
        JsonNode schema = ReviewM0ReportSchemaTest.resource("review-m0-integration-selector.schema.json");
        JsonNode properties = schema.path("properties");
        JsonNode ready = schema.path("allOf").path(1).path("then").path("properties");
        JsonNode pending = schema.path("allOf").path(0).path("then").path("properties");
        assertThat(properties.path("adapterId").path("type").asText()).isEqualTo("string");
        assertThat(properties.path("testClasses").path("minItems").asInt()).isOne();
        assertThat(properties.path("targetSelectors").path("minProperties").asInt()).isEqualTo(15);
        assertThat(properties.path("targetSelectors").path("maxProperties").asInt()).isEqualTo(15);
        assertThat(properties.path("staticScanRoots").path("minItems").asInt()).isOne();
        assertThat(pending.path("pendingReasons").path("minItems").asInt()).isOne();
        assertThat(ready.path("pendingReasons").path("maxItems").asInt()).isZero();
    }

    @Test
    void selectorSchemaRequiresExactTestMethodsAndRelativeStaticRoots() throws Exception {
        JsonNode schema = ReviewM0ReportSchemaTest.resource("review-m0-integration-selector.schema.json");
        JsonNode target = schema.path("$defs").path("targetSelector");
        assertThat(ReviewM0ReportSchemaTest.textValues(target.path("required")))
                .containsExactly("className", "methodName");
        assertThat(target.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").path("staticScanRoots").path("items").path("pattern").asText())
                .contains("\\.\\.");
        assertThat(schema.path("properties").path("evidenceSystemProperty").path("const").asText())
                .isEqualTo("review.m0.acceptance.evidence.path");
    }

    private static JsonNode selector() throws Exception {
        try (InputStream input = ReviewM0IntegrationSelectorContractTest.class.getResourceAsStream(
                "/review-m0/acceptance/review-m0-integration-selector.json")) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
