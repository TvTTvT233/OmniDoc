package com.javaee.aiservice.review.m0.evidence;

import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.EvidenceKind;
import com.javaee.aiservice.review.m0.model.EvidenceSide;
import com.javaee.aiservice.review.m0.model.RequirementAuthorship;
import com.javaee.aiservice.review.m0.review.CapturedReviewInput;
import com.javaee.aiservice.review.m0.review.FormalChecklist;
import com.javaee.aiservice.review.m0.review.ReviewInputManifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds canonical evidence metadata and deliberately excludes material/official text and object keys. */
public final class ReviewEvidenceFactory {
    public List<EvidenceDraft> requirementRuleEvidence(
            CapturedReviewInput input,
            FormalChecklist.Item item,
            String ruleId
    ) {
        List<EvidenceDraft> result = new ArrayList<>();
        Map<String, Object> rule = ruleValue(input, item, ruleId);
        if (item.authorship() == RequirementAuthorship.USER_REWRITTEN || item.sourceRefs().isEmpty()) {
            result.add(new EvidenceDraft(EvidenceSide.RULE, EvidenceKind.USER_RULE_REVISION, rule));
        }
        for (FormalChecklist.SourceRef sourceRef : item.sourceRefs()) {
            Map<String, Object> official = new LinkedHashMap<>(rule);
            official.put("officialSourceRef", sourceRef.canonicalValue());
            result.add(new EvidenceDraft(
                    EvidenceSide.RULE, EvidenceKind.OFFICIAL_BASIS_LOCATION, official));
        }
        if (result.isEmpty()) {
            result.add(new EvidenceDraft(EvidenceSide.RULE, EvidenceKind.USER_RULE_REVISION, rule));
        }
        return List.copyOf(result);
    }

    public EvidenceDraft globalRule(String ruleId, Map<String, Object> details) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", FormalChecklist.CONTRACT_VERSION);
        value.put("details", details);
        value.put("ruleId", ruleId);
        value.put("ruleRevision", "1");
        return new EvidenceDraft(EvidenceSide.RULE, EvidenceKind.GLOBAL_M0_POLICY, value);
    }

    public EvidenceDraft materialItem(CapturedReviewInput input, ReviewInputManifest.MaterialFact material) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("checklistSnapshotId", input.checklist().snapshotId());
        value.put("contentDigest", material.contentDigest());
        value.put("entryIndex", material.entryIndex());
        value.put("entryPath", material.normalizedEntryPath());
        value.put("formalFileType", material.formalFileType());
        value.put("kind", material.kind());
        value.put("materialId", material.materialId());
        value.put("materialSetRevision", input.manifest().materialSetRevision());
        value.put("sourceId", material.sourceId());
        value.put("status", material.status());
        value.put("submissionFileName", material.submissionFileName());
        return new EvidenceDraft(EvidenceSide.MATERIAL, EvidenceKind.MATERIAL_ITEM_SNAPSHOT, value);
    }

    public EvidenceDraft diagnosticItem(
            CapturedReviewInput input,
            ReviewInputManifest.DiagnosticFact diagnostic
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("diagnosticId", diagnostic.diagnosticId());
        value.put("failureStage", diagnostic.failureStage());
        value.put("locatorDigest", diagnostic.locatorDigest());
        value.put("materialSetRevision", input.manifest().materialSetRevision());
        value.put("reason", diagnostic.reason());
        value.put("subjectId", diagnostic.subjectId());
        value.put("subjectType", diagnostic.subjectType());
        return new EvidenceDraft(EvidenceSide.MATERIAL, EvidenceKind.MATERIAL_ITEM_SNAPSHOT, value);
    }

    public EvidenceDraft missingMaterialSet(
            CapturedReviewInput input,
            FormalChecklist.Item item,
            int confirmedAvailableCount
    ) {
        ReviewInputManifest manifest = input.manifest();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("assignmentRevision", manifest.assignmentRevision());
        value.put("confirmedAssignments", manifest.assignments().stream()
                .map(ReviewInputManifest.AssignmentFact::canonicalValue).toList());
        value.put("confirmedAvailableCount", confirmedAvailableCount);
        value.put("diagnostics", manifest.diagnostics().stream()
                .map(ReviewInputManifest.DiagnosticFact::canonicalValue).toList());
        value.put("expectedCount", item.minimumCount());
        value.put("materialSetDigest", manifest.uploadMaterialSetDigest());
        value.put("materialSetRevision", manifest.materialSetRevision());
        // Completeness evidence intentionally excludes logical names, so a logical rename does not
        // invalidate otherwise identical material-set evidence.
        value.put("materials", manifest.materials().stream()
                .map(ReviewInputManifest.MaterialFact::canonicalValueWithoutName).toList());
        value.put("requirementId", item.requirementId());
        value.put("sources", manifest.sources().stream()
                .map(ReviewInputManifest.SourceFact::canonicalValue).toList());
        value.put("uploadConfirmationId", manifest.uploadConfirmationId());
        return new EvidenceDraft(EvidenceSide.MATERIAL, EvidenceKind.MATERIAL_SET_SNAPSHOT, value);
    }

    private static Map<String, Object> ruleValue(
            CapturedReviewInput input,
            FormalChecklist.Item item,
            String ruleId
    ) {
        Map<String, Object> fileNameRule = new LinkedHashMap<>();
        fileNameRule.put("caseSensitive", item.fileNameRule().caseSensitive());
        fileNameRule.put("kind", item.fileNameRule().kind());
        fileNameRule.put("pattern", item.fileNameRule().pattern());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("authorship", item.authorship());
        value.put("checklistRevision", input.checklist().checklistRevision());
        value.put("checklistRevisionId", input.checklist().checklistRevisionId());
        value.put("checklistSnapshotDigest", input.checklist().snapshotDigest());
        value.put("checklistSnapshotId", input.checklist().snapshotId());
        value.put("fileNameRule", fileNameRule);
        value.put("itemVersion", item.itemVersion());
        value.put("minimumCount", item.minimumCount());
        value.put("origin", item.origin());
        value.put("requirementId", item.requirementId());
        value.put("ruleId", ruleId);
        value.put("ruleRevision", item.ruleRevision());
        return value;
    }
}
