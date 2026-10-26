package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.evidence.EvidenceDraft;
import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.DiagnosticSubjectType;
import com.javaee.aiservice.review.m0.model.EvidenceSide;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Purely local M0 completeness and logical-file-name engine. */
public final class DeterministicReviewEngine {
    public static final String ENGINE_VERSION = "m0-deterministic-v1";
    private static final String GLOBAL_SAFE_NAME_RULE = "GLOBAL_M0_SAFE_LOGICAL_FILE_NAME_V1";
    private static final String GLOBAL_TYPE_RULE = "GLOBAL_M0_FORMAL_TYPE_MATCH_V1";

    private final SafeRegexMatcher regexMatcher;
    private final ReviewEvidenceFactory evidenceFactory;

    public DeterministicReviewEngine(SafeRegexMatcher regexMatcher, ReviewEvidenceFactory evidenceFactory) {
        this.regexMatcher = Objects.requireNonNull(regexMatcher);
        this.evidenceFactory = Objects.requireNonNull(evidenceFactory);
    }

    public List<FindingDraft> evaluateFull(CapturedReviewInput input) {
        List<FindingDraft> findings = new ArrayList<>(evaluateCompletenessAndCannot(input));
        findings.addAll(evaluateNaming(input, ignored -> true));
        return normalized(findings);
    }

    public List<FindingDraft> evaluateIncremental(
            CapturedReviewInput input,
            List<FindingDraft> baseFindings,
            IncrementalPlan plan
    ) {
        List<FindingDraft> findings = new ArrayList<>();
        if (plan.nameOnly() && !plan.recomputeCompleteness()) {
            baseFindings.stream()
                    .filter(item -> item.materialId() == null
                            || !plan.changedMaterialIds().contains(item.materialId()))
                    .forEach(findings::add);
            cannotConfirmFindings(input).stream()
                    .filter(item -> item.materialId() != null
                            && plan.changedMaterialIds().contains(item.materialId()))
                    .forEach(findings::add);
        } else {
            baseFindings.stream()
                    .filter(item -> item.type() == FindingType.FILE_NAME_NONCOMPLIANT)
                    .filter(item -> item.materialId() == null
                            || !plan.changedMaterialIds().contains(item.materialId()))
                    .forEach(findings::add);
            findings.addAll(evaluateCompletenessAndCannot(input));
        }
        findings.addAll(evaluateNaming(input, plan.changedMaterialIds()::contains));
        return normalized(findings);
    }

    public List<FindingDraft> evaluateCompletenessAndCannot(CapturedReviewInput input) {
        List<FindingDraft> cannotConfirm = cannotConfirmFindings(input);
        if (!cannotConfirm.isEmpty()) {
            return normalized(cannotConfirm);
        }
        Map<String, ReviewInputManifest.MaterialFact> materials = materialsById(input.manifest());
        List<FindingDraft> missing = new ArrayList<>();
        for (FormalChecklist.Item item : input.checklist().items()) {
            if (item.applicability() != RequirementApplicability.APPLIES) continue;
            int available = (int) input.manifest().assignments().stream()
                    .filter(assignment -> assignment.status() == AssignmentStatus.USER_CONFIRMED)
                    .filter(assignment -> assignment.requirementId().equals(item.requirementId()))
                    .map(ReviewInputManifest.AssignmentFact::materialId)
                    .distinct()
                    .map(materials::get)
                    .filter(Objects::nonNull)
                    .filter(material -> material.status() == MaterialStatus.AVAILABLE)
                    .count();
            if (available < item.minimumCount()) {
                String ruleId = requirementRuleId(input, item, "MINIMUM_COUNT");
                List<EvidenceDraft> evidence = new ArrayList<>(
                        evidenceFactory.requirementRuleEvidence(input, item, ruleId));
                evidence.add(evidenceFactory.missingMaterialSet(input, item, available));
                missing.add(finding(
                        FindingType.MISSING, null, item.requirementId(), null, ruleId,
                        "A confirmed checklist requirement does not have enough assigned available materials",
                        evidence));
            }
        }
        return normalized(missing);
    }

    public List<FindingDraft> evaluateNaming(
            CapturedReviewInput input,
            Predicate<String> materialSelection
    ) {
        Map<String, ReviewInputManifest.MaterialFact> materials = materialsById(input.manifest());
        Map<String, FormalChecklist.Item> requirements = new HashMap<>();
        input.checklist().items().forEach(item -> requirements.put(item.requirementId(), item));
        Set<String> typeMismatches = derivedTypeMismatchIds(input.manifest());
        List<FindingDraft> findings = new ArrayList<>();
        for (ReviewInputManifest.MaterialFact material : input.manifest().materials()) {
            if (!materialSelection.test(material.materialId())
                    || material.status() != MaterialStatus.AVAILABLE
                    || typeMismatches.contains(material.materialId())) {
                continue;
            }
            String unsafeReason = unsafeNameReason(material.submissionFileName());
            if (unsafeReason != null) {
                EvidenceDraft rule = evidenceFactory.globalRule(
                        GLOBAL_SAFE_NAME_RULE, Map.of("safetyReason", unsafeReason));
                findings.add(finding(
                        FindingType.FILE_NAME_NONCOMPLIANT, null, null, material.materialId(),
                        GLOBAL_SAFE_NAME_RULE, "Logical submission file name violates the global safety policy",
                        List.of(rule, evidenceFactory.materialItem(input, material))));
            }
        }
        for (ReviewInputManifest.AssignmentFact assignment : input.manifest().assignments()) {
            if (!materialSelection.test(assignment.materialId())) continue;
            ReviewInputManifest.MaterialFact material = materials.get(assignment.materialId());
            FormalChecklist.Item item = requirements.get(assignment.requirementId());
            if (material == null || item == null || item.applicability() != RequirementApplicability.APPLIES
                    || material.status() != MaterialStatus.AVAILABLE
                    || typeMismatches.contains(material.materialId())
                    || item.fileNameRule().kind() == FileNameRuleKind.NONE) {
                continue;
            }
            if (!matches(item.fileNameRule(), material.submissionFileName())) {
                String ruleId = requirementRuleId(input, item, "FILE_NAME_" + item.fileNameRule().kind());
                List<EvidenceDraft> evidence = new ArrayList<>(
                        evidenceFactory.requirementRuleEvidence(input, item, ruleId));
                evidence.add(evidenceFactory.materialItem(input, material));
                findings.add(finding(
                        FindingType.FILE_NAME_NONCOMPLIANT, null, item.requirementId(),
                        material.materialId(), ruleId,
                        "Logical submission file name does not satisfy its confirmed checklist rule",
                        evidence));
            }
        }
        return normalized(findings);
    }

    private List<FindingDraft> cannotConfirmFindings(CapturedReviewInput input) {
        Map<String, FindingDraft> findings = new LinkedHashMap<>();
        for (ReviewInputManifest.MaterialFact material : input.manifest().materials()) {
            if (material.status() == MaterialStatus.CANNOT_CONFIRM) {
                String subjectKey = "MATERIAL:" + material.materialId() + ":" + material.cannotConfirmReason();
                findings.put(subjectKey, cannotFromMaterial(input, material, material.cannotConfirmReason()));
            } else if (!extensionMatchesType(material)) {
                String subjectKey = "MATERIAL:" + material.materialId() + ":" + CannotConfirmReason.TYPE_MISMATCH;
                findings.put(subjectKey, cannotFromMaterial(input, material, CannotConfirmReason.TYPE_MISMATCH));
            }
        }
        for (ReviewInputManifest.DiagnosticFact diagnostic : input.manifest().diagnostics()) {
            String subjectKey = diagnostic.subjectType() + ":" + diagnostic.subjectId() + ":" + diagnostic.reason();
            findings.putIfAbsent(subjectKey, cannotFromDiagnostic(input, diagnostic));
        }
        return normalized(new ArrayList<>(findings.values()));
    }

    private FindingDraft cannotFromMaterial(
            CapturedReviewInput input,
            ReviewInputManifest.MaterialFact material,
            CannotConfirmReason reason
    ) {
        String ruleId = "GLOBAL_M0_INPUT_RELIABILITY_" + reason;
        EvidenceDraft rule = evidenceFactory.globalRule(ruleId, Map.of("reason", reason));
        return finding(
                FindingType.CANNOT_CONFIRM, reason, null, material.materialId(), ruleId,
                "A current submission input cannot be reliably confirmed",
                List.of(rule, evidenceFactory.materialItem(input, material)));
    }

    private FindingDraft cannotFromDiagnostic(
            CapturedReviewInput input,
            ReviewInputManifest.DiagnosticFact diagnostic
    ) {
        String ruleId = "GLOBAL_M0_INPUT_RELIABILITY_" + diagnostic.reason();
        EvidenceDraft rule = evidenceFactory.globalRule(ruleId, Map.of("reason", diagnostic.reason()));
        String materialId = diagnostic.subjectType() == DiagnosticSubjectType.MATERIAL
                ? diagnostic.subjectId() : null;
        return finding(
                FindingType.CANNOT_CONFIRM, diagnostic.reason(), null, materialId, ruleId,
                "A current submission input cannot be reliably confirmed",
                List.of(rule, evidenceFactory.diagnosticItem(input, diagnostic)));
    }

    private boolean matches(FileNameRule rule, String submissionFileName) {
        String normalized = Normalizer.normalize(submissionFileName, Normalizer.Form.NFC);
        return switch (rule.kind()) {
            case NONE -> true;
            case EXACT -> Boolean.TRUE.equals(rule.caseSensitive())
                    ? normalized.equals(rule.pattern())
                    : normalized.equalsIgnoreCase(rule.pattern());
            case REGEX -> regexMatcher.matches(rule.pattern(), Boolean.TRUE.equals(rule.caseSensitive()), normalized);
        };
    }

    private static Set<String> derivedTypeMismatchIds(ReviewInputManifest manifest) {
        Set<String> ids = new LinkedHashSet<>();
        manifest.materials().stream()
                .filter(material -> material.status() == MaterialStatus.AVAILABLE)
                .filter(material -> !extensionMatchesType(material))
                .map(ReviewInputManifest.MaterialFact::materialId)
                .forEach(ids::add);
        return ids;
    }

    private static boolean extensionMatchesType(ReviewInputManifest.MaterialFact material) {
        return Normalizer.normalize(material.submissionFileName(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).endsWith(material.formalFileType().extension());
    }

    private static String unsafeNameReason(String name) {
        if (name == null || name.isBlank() || name.length() > 1024) return "INVALID_LENGTH";
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
        if (normalized.equals(".") || normalized.equals("..")) return "DOT_PATH";
        if (normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0) return "PATH_SEPARATOR";
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (codePoint == 0 || Character.isISOControl(codePoint)) return "CONTROL_CHARACTER";
            offset += Character.charCount(codePoint);
        }
        return null;
    }

    private static FindingDraft finding(
            FindingType type,
            CannotConfirmReason reason,
            String requirementId,
            String materialId,
            String ruleId,
            String message,
            List<EvidenceDraft> evidence
    ) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("findingType", type);
        key.put("materialId", materialId);
        key.put("reason", reason);
        key.put("requirementId", requirementId);
        key.put("ruleId", ruleId);
        return new FindingDraft(
                type, reason, requirementId, materialId, CanonicalJson.sha256(key), message, evidence);
    }

    private static String requirementRuleId(
            CapturedReviewInput input,
            FormalChecklist.Item item,
            String suffix
    ) {
        return "CHECKLIST:" + input.checklist().snapshotId() + ":" + item.requirementId()
                + ":" + item.itemVersion() + ":" + suffix;
    }

    private static Map<String, ReviewInputManifest.MaterialFact> materialsById(ReviewInputManifest manifest) {
        Map<String, ReviewInputManifest.MaterialFact> result = new HashMap<>();
        manifest.materials().forEach(item -> result.put(item.materialId(), item));
        return result;
    }

    private static List<FindingDraft> normalized(List<FindingDraft> findings) {
        return List.copyOf(findings).stream()
                .sorted(Comparator.comparing(FindingDraft::normalizedKey))
                .toList();
    }
}
