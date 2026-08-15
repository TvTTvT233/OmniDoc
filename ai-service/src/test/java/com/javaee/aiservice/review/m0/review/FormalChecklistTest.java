package com.javaee.aiservice.review.m0.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormalChecklistTest {
    private static final String QUOTED_OFFICIAL_TEXT = "synthetic official excerpt must not be copied";

    @Test
    void strictlyReadsPublishedSnapshotButExcludesQuotedOfficialTextFromEvidence() {
        ChecklistSnapshot snapshot = snapshot("APPLIES", ChecklistLifecycle.CONFIRMED, null);

        FormalChecklist checklist = FormalChecklist.from(snapshot);
        CapturedReviewInput input = M005Fixtures.empty(checklist);
        List<FindingDraft> findings = new DeterministicReviewEngine(
                (pattern, caseSensitive, value) -> true, new ReviewEvidenceFactory())
                .evaluateFull(input);

        assertThat(checklist.items()).hasSize(1);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).evidence())
                .allSatisfy(evidence -> assertThat(evidence.refJson())
                        .doesNotContain(QUOTED_OFFICIAL_TEXT));
        assertThat(findings.get(0).evidence())
                .anySatisfy(evidence -> assertThat(evidence.refJson())
                        .contains("officialSourceRef", "entryDigest", "basis-1"));
    }

    @Test
    void staleOrSupersededSnapshotIsNotAcceptedAsFormalInput() {
        ChecklistSnapshot stale = snapshot("APPLIES", ChecklistLifecycle.STALE_BASIS, null);
        ChecklistSnapshot superseded = snapshot(
                "APPLIES", ChecklistLifecycle.CONFIRMED, Instant.parse("2026-08-15T00:01:00Z"));

        assertRejected(stale, ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED);
        assertRejected(superseded, ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED);
    }

    @Test
    void unresolvedConditionalIsRejectedEvenIfStoredAsConfirmed() {
        assertRejected(
                snapshot("NEEDS_USER_DECISION", ChecklistLifecycle.CONFIRMED, null),
                ReviewM0ErrorCode.REVIEW_M0_CONDITIONAL_UNRESOLVED);
    }

    @Test
    void invalidPublishedRegexIsAStoredRuleFailureNotANameFinding() throws Exception {
        ChecklistSnapshot valid = snapshot("APPLIES", ChecklistLifecycle.CONFIRMED, null);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(valid.snapshotJson());
        ObjectNode rule = (ObjectNode) root.path("items").get(0).path("fileNameRule");
        rule.put("kind", "REGEX");
        rule.put("pattern", "(");
        rule.put("caseSensitive", true);
        Object canonical = mapper.convertValue(root, Object.class);
        ChecklistSnapshot corrupt = new ChecklistSnapshot(
                valid.checklistSnapshotId(), valid.caseId(), valid.checklistRevisionId(),
                valid.basisRevision(), valid.checklistRevision(), CanonicalJson.write(canonical),
                CanonicalJson.sha256(canonical), valid.lifecycle(), valid.confirmedBy(),
                valid.confirmedAt(), null);

        assertRejected(corrupt, ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE);
    }

    private static void assertRejected(ChecklistSnapshot snapshot, ReviewM0ErrorCode expected) {
        assertThatThrownBy(() -> FormalChecklist.from(snapshot))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(expected);
    }

    private static ChecklistSnapshot snapshot(
            String applicability,
            ChecklistLifecycle lifecycle,
            Instant supersededAt
    ) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("entryPath", null);
        locator.put("page", 3);
        locator.put("sheet", null);
        locator.put("cellRange", null);
        locator.put("block", null);
        locator.put("entryDigest", null);
        Map<String, Object> sourceRef = new LinkedHashMap<>();
        sourceRef.put("basisId", "basis-1");
        sourceRef.put("sourceLocator", locator);
        sourceRef.put("quotedText", QUOTED_OFFICIAL_TEXT);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("kind", "NONE");
        rule.put("pattern", null);
        rule.put("caseSensitive", null);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("requirementId", "requirement-1");
        item.put("displayName", "Synthetic requirement");
        item.put("enabled", true);
        item.put("applicability", applicability);
        item.put("minimumCount", 1);
        item.put("fileNameRule", rule);
        item.put("origin", "OFFICIAL_EXTRACTED");
        item.put("authorship", "EXTRACTED_TEXT");
        item.put("itemVersion", 2);
        item.put("sourceRefs", List.of(sourceRef));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("contractVersion", FormalChecklist.CONTRACT_VERSION);
        root.put("caseId", "case-1");
        root.put("basisRevision", 1);
        root.put("checklistRevision", 7);
        root.put("items", List.of(item));
        String json = CanonicalJson.write(root);
        return new ChecklistSnapshot(
                "clsnap-1", "case-1", "clrev-1", 1, 7, json,
                CanonicalJson.sha256(root), lifecycle, "owner-1",
                Instant.parse("2026-08-15T00:00:00Z"), supersededAt);
    }
}
