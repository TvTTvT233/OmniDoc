package com.javaee.aiservice.review.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0FixtureContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> REQUIRED_TARGETS = java.util.stream.IntStream.rangeClosed(1, 15)
            .mapToObj(value -> "M0-P0-%03d".formatted(value))
            .toList();

    @Test
    void manifestIsSyntheticCompleteAndHasUniqueScenarioIds() throws Exception {
        JsonNode fixture = fixture();
        List<String> targets = textValues(fixture.path("requiredTargetIds"));
        List<String> scenarioIds = new ArrayList<>();
        Set<String> coveredTargets = new HashSet<>();
        fixture.path("scenarios").forEach(item -> {
            scenarioIds.add(item.path("id").asText());
            coveredTargets.add(item.path("targetId").asText());
        });

        assertThat(fixture.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(fixture.path("fixtureSetId").asText()).isEqualTo("review-m0-p0-contract-v1");
        assertThat(fixture.path("synthetic").asBoolean()).isTrue();
        assertThat(fixture.path("containsRealUserData").asBoolean()).isFalse();
        assertThat(targets).containsExactlyElementsOf(REQUIRED_TARGETS);
        assertThat(new HashSet<>(scenarioIds)).hasSameSizeAs(scenarioIds);
        assertThat(coveredTargets).containsExactlyInAnyOrderElementsOf(REQUIRED_TARGETS);
    }

    @Test
    void m0P0_001_inventoryMustBeConfirmedBeforeReview() throws Exception {
        assertExpectation("m0-inventory-unconfirmed-blocks-review", expect -> {
            assertThat(expect.path("actionAllowed").asBoolean()).isFalse();
            assertThat(expect.path("reviewCreated").asBoolean()).isFalse();
            assertThat(expect.path("reasonCode").asText()).isEqualTo("INVENTORY_CONFIRMATION_REQUIRED");
        });
        assertThat(scenario("m0-inventory-confirmed-allows-review").path("expect").path("actionAllowed").asBoolean())
                .isTrue();
    }

    @Test
    void m0P0_002_officialRulesNeedAnchorsAndUserRulesKeepProvenance() throws Exception {
        assertExpectation("m0-official-rule-requires-anchor", expect -> {
            assertThat(expect.path("definitiveFindingAllowed").asBoolean()).isFalse();
            assertThat(expect.path("disposition").asText()).isEqualTo("CANNOT_CONFIRM");
        });
        assertThat(scenario("m0-official-rule-with-dual-anchors").path("expect")
                .path("definitiveFindingAllowed").asBoolean()).isTrue();
        for (String id : List.of("m0-user-added-rule-remains-user", "m0-user-rewrite-does-not-become-official")) {
            JsonNode item = scenario(id);
            assertThat(item.path("given").path("provenance").asText()).startsWith("USER_");
            assertThat(item.path("expect").path("storedProvenance").asText()).startsWith("USER_");
            assertThat(item.path("expect").path("official").asBoolean()).isFalse();
        }
    }

    @Test
    void m0P0_003_missingRequiresUploadCompletion() throws Exception {
        JsonNode open = scenario("m0-upload-open-cannot-be-missing").path("expect");
        assertThat(open.path("classification").asText()).isEqualTo("CANNOT_CONFIRM");
        assertThat(open.path("forbiddenClassification").asText()).isEqualTo("MISSING");
        assertThat(scenario("m0-upload-confirmed-can-be-missing").path("expect")
                .path("classification").asText()).isEqualTo("MISSING");
    }

    @Test
    void m0P0_004_unreadableInputsAreCannotConfirmNotMissing() throws Exception {
        Map<String, String> reasons = Map.of(
                "m0-encrypted-file-cannot-confirm", "FILE_ENCRYPTED",
                "m0-corrupt-file-cannot-confirm", "FILE_CORRUPT",
                "m0-ocr-failure-cannot-confirm", "OCR_FAILED");
        reasons.forEach((id, reason) -> {
            JsonNode expect = uncheckedScenario(id).path("expect");
            assertThat(expect.path("classification").asText()).isEqualTo("CANNOT_CONFIRM");
            assertThat(expect.path("reasonCode").asText()).isEqualTo(reason);
        });
    }

    @Test
    void m0P0_005_onlyExplicitNamingRulesPermitDefinitiveFindings() throws Exception {
        assertThat(scenario("m0-explicit-naming-rule-is-deterministic").path("expect")
                .path("definitiveFindingAllowed").asBoolean()).isTrue();
        JsonNode ambiguous = scenario("m0-ambiguous-naming-rule-cannot-decide").path("expect");
        assertThat(ambiguous.path("definitiveFindingAllowed").asBoolean()).isFalse();
        assertThat(ambiguous.path("classification").asText()).isEqualTo("CANNOT_CONFIRM");
    }

    @Test
    void m0P0_006_downloadCopyPreservesOriginalObjectKeyAndContentHash() throws Exception {
        JsonNode item = scenario("m0-download-copy-renames-only-submission-name");
        JsonNode before = item.path("given").path("before");
        JsonNode after = item.path("expect").path("after");
        assertThat(after.path("original").asText()).isEqualTo(before.path("original").asText());
        assertThat(after.path("objectKey").asText()).isEqualTo(before.path("objectKey").asText());
        assertThat(after.path("contentHash").asText()).isEqualTo(before.path("contentHash").asText());
        assertThat(after.path("submissionFileName").asText())
                .isEqualTo(item.path("given").path("requestedSubmissionFileName").asText())
                .isNotEqualTo(before.path("submissionFileName").asText());
        assertThat(item.path("expect").path("downloadIsDerivedCopy").asBoolean()).isTrue();
    }

    @Test
    void m0P0_007_ignoredItemsRemainAndCannotBecomeReady() throws Exception {
        JsonNode expect = scenario("m0-ignored-item-remains-not-ready").path("expect");
        assertThat(expect.path("itemRetained").asBoolean()).isTrue();
        assertThat(expect.path("transitionAllowed").asBoolean()).isFalse();
        assertThat(expect.path("resultState").asText()).isEqualTo("IGNORED");
    }

    @Test
    void m0P0_008_idempotentRetriesHaveOneStableResult() throws Exception {
        JsonNode expect = scenario("m0-idempotent-repeat-has-one-result").path("expect");
        assertThat(expect.path("createdResults").asInt()).isOne();
        assertThat(expect.path("duplicateResults").asInt()).isZero();
        assertThat(expect.path("sameResultIdentity").asBoolean()).isTrue();
    }

    @Test
    void m0P0_009_recoveryResumesWithoutDuplicateResults() throws Exception {
        JsonNode expect = scenario("m0-recovery-resumes-from-checkpoint").path("expect");
        assertThat(expect.path("resumeFrom").asText()).isEqualTo("EXTRACTED");
        assertThat(expect.path("reprocessedCompletedStages").asInt()).isZero();
        assertThat(expect.path("duplicateResults").asInt()).isZero();
    }

    @Test
    void m0P0_010_incrementalReviewMarksOldSnapshotOutdated() throws Exception {
        JsonNode incremental = scenario("m0-incremental-review-changed-file-only").path("expect");
        assertThat(textValues(incremental.path("reReviewedMaterialIds"))).containsExactly("material-synthetic-02");
        assertThat(textValues(incremental.path("reusedMaterialIds"))).containsExactly("material-synthetic-01");
        JsonNode stale = scenario("m0-old-result-becomes-outdated").path("expect");
        assertThat(stale.path("reviewState").asText()).isEqualTo("OUTDATED");
        assertThat(stale.path("m0Ready").asBoolean()).isFalse();
    }

    @Test
    void m0P0_011_crossUserReadAndDownloadAreDeniedWithoutExistenceLeak() throws Exception {
        for (String id : List.of("m0-cross-user-read-is-hidden", "m0-cross-user-download-is-hidden")) {
            JsonNode expect = scenario(id).path("expect");
            assertThat(expect.path("allowed").asBoolean()).isFalse();
            assertThat(expect.path("publicErrorCode").asText()).isEqualTo("REVIEW_NOT_FOUND");
        }
    }

    @Test
    void m0P0_012_machineReportFixtureForbidsSensitivePayloadFields() throws Exception {
        JsonNode item = scenario("m0-sensitive-fields-not-in-report");
        assertThat(item.path("given").path("fields").size()).isGreaterThanOrEqualTo(6);
        assertThat(item.path("expect").path("allowedInMachineReport")).isEmpty();
        assertThat(item.path("expect").path("staticLeakCount").asInt()).isZero();
    }

    @Test
    void m0P0_013_m0ForbidsTypoUnfilledEligibilityAndConsistencyConclusions() throws Exception {
        JsonNode expect = scenario("m0-forbidden-conclusions-absent").path("expect");
        assertThat(textValues(expect.path("forbiddenConclusionKinds")))
                .containsExactly("TYPO", "UNFILLED_FIELD", "ELIGIBILITY", "CROSS_MATERIAL_CONSISTENCY");
        assertThat(expect.path("emittedCount").asInt()).isZero();
    }

    @Test
    void m0P0_014_allFormalFormatsHaveSignaturesAndConservativeDisposition() throws Exception {
        JsonNode formats = fixture().path("formalFormats");
        Set<String> names = new HashSet<>();
        formats.forEach(item -> {
            names.add(item.path("format").asText());
            assertThat(item.path("extension").asText()).startsWith(".");
            assertThat(item.path("mediaType").asText()).contains("/");
            assertThat(item.path("signatureHex").asText()).matches("[0-9A-F]+");
            assertThat(item.path("expectedDisposition").asText()).isIn("ACCEPT", "CANNOT_CONFIRM");
        });
        assertThat(names).containsExactlyInAnyOrder("PDF", "DOCX", "XLSX", "JPG", "PNG", "ZIP");
    }

    @Test
    void m0P0_015_zipSafetyBoundariesFailClosed() throws Exception {
        JsonNode boundaries = fixture().path("zipBoundaries");
        assertThat(boundaries.size()).isGreaterThanOrEqualTo(9);
        List<JsonNode> boundaryItems = new ArrayList<>();
        boundaries.forEach(boundaryItems::add);
        Map<String, JsonNode> byId = boundaryItems.stream()
                .collect(Collectors.toMap(item -> item.path("id").asText(), Function.identity()));
        assertThat(byId.get("zip-safe-flat-archive").path("expectedDisposition").asText()).isEqualTo("ACCEPT");
        for (Map.Entry<String, JsonNode> entry : byId.entrySet()) {
            if (entry.getKey().equals("zip-safe-flat-archive")) {
                continue;
            }
            assertThat(entry.getValue().path("expectedDisposition").asText()).isIn("REJECT", "CANNOT_CONFIRM");
        }
        assertThat(byId.keySet()).contains(
                "zip-parent-traversal", "zip-absolute-path", "zip-symlink-entry",
                "zip-expanded-size-limit", "zip-compression-ratio-limit", "zip-entry-count-limit",
                "zip-nested-archive", "zip-encrypted-entry", "zip-normalized-duplicate",
                "zip-extension-signature-mismatch");
    }

    private static void assertExpectation(String id, java.util.function.Consumer<JsonNode> assertion) throws Exception {
        assertion.accept(scenario(id).path("expect"));
    }

    private static JsonNode scenario(String id) throws Exception {
        return scenarioFrom(fixture(), id);
    }

    private static JsonNode uncheckedScenario(String id) {
        try {
            return scenario(id);
        } catch (Exception error) {
            throw new AssertionError("Unable to load fixture scenario " + id, error);
        }
    }

    private static JsonNode scenarioFrom(JsonNode fixture, String id) {
        for (JsonNode item : fixture.path("scenarios")) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("Missing fixture scenario " + id);
    }

    private static JsonNode fixture() throws Exception {
        try (InputStream input = ReviewM0FixtureContractTest.class.getResourceAsStream(
                "/review/m0/acceptance/review-m0-p0-fixtures.json")) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }
}
