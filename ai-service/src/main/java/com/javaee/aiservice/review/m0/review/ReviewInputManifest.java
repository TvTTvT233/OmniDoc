package com.javaee.aiservice.review.m0.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.ConfirmedAssignmentFingerprint;
import com.javaee.aiservice.review.m0.model.DiagnosticSubjectType;
import com.javaee.aiservice.review.m0.model.DigestEntry;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.LogicalNameFingerprint;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.OfficialBasisRole;
import com.javaee.aiservice.review.m0.model.ReviewInputFingerprint;
import com.javaee.aiservice.review.m0.model.ReviewInputFingerprintSpec;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.UnicodeCodePointComparator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Body-free immutable manifest stored with every formal review Job. It is sufficient to
 * validate an incremental base without relying on mutable material rows.
 */
public record ReviewInputManifest(
        String contractVersion,
        String engineVersion,
        String caseId,
        long basisRevision,
        List<BasisFact> bases,
        String checklistSnapshotId,
        String checklistSnapshotDigest,
        long materialSetRevision,
        long assignmentRevision,
        long nameRevision,
        String uploadConfirmationId,
        String uploadMaterialSetDigest,
        List<SourceFact> sources,
        List<MaterialFact> materials,
        List<AssignmentFact> assignments,
        List<DiagnosticFact> diagnostics
) {
    private static final ObjectMapper READER = new ObjectMapper();

    public ReviewInputManifest {
        bases = sorted(bases, Comparator.comparing(
                BasisFact::basisId, UnicodeCodePointComparator.INSTANCE));
        sources = sorted(sources, Comparator.comparing(
                SourceFact::sourceId, UnicodeCodePointComparator.INSTANCE));
        materials = sorted(materials, Comparator.comparing(
                MaterialFact::materialId, UnicodeCodePointComparator.INSTANCE));
        assignments = sorted(assignments, Comparator.comparing(
                AssignmentFact::stableId, UnicodeCodePointComparator.INSTANCE));
        diagnostics = sorted(diagnostics, Comparator.comparing(
                DiagnosticFact::stableId, UnicodeCodePointComparator.INSTANCE));
        requireText(contractVersion, "contractVersion");
        requireText(engineVersion, "engineVersion");
        requireText(caseId, "caseId");
        requireText(checklistSnapshotId, "checklistSnapshotId");
        requireDigest(checklistSnapshotDigest, "checklistSnapshotDigest");
        requireText(uploadConfirmationId, "uploadConfirmationId");
        requireDigest(uploadMaterialSetDigest, "uploadMaterialSetDigest");
    }

    public ReviewInputFingerprint fingerprint() {
        List<DigestEntry> materialDigests = new ArrayList<>();
        for (SourceFact source : sources) {
            materialDigests.add(new DigestEntry("source:" + source.sourceId(), source.digest()));
        }
        for (MaterialFact material : materials) {
            materialDigests.add(new DigestEntry("material:" + material.materialId(), material.digest()));
        }
        for (DiagnosticFact diagnostic : diagnostics) {
            materialDigests.add(new DigestEntry("diagnostic:" + diagnostic.stableId(), diagnostic.digest()));
        }
        return ReviewInputFingerprint.calculate(new ReviewInputFingerprintSpec(
                contractVersion,
                caseId,
                basisRevision,
                bases.stream().map(item -> new DigestEntry(item.basisId(), item.digest())).toList(),
                checklistSnapshotId,
                checklistSnapshotDigest,
                materialSetRevision,
                materialDigests,
                assignmentRevision,
                assignments.stream().map(item -> new ConfirmedAssignmentFingerprint(
                        item.materialId(), item.requirementId())).toList(),
                nameRevision,
                materials.stream().map(item -> new LogicalNameFingerprint(
                        item.materialId(), item.submissionFileName())).toList(),
                uploadConfirmationId,
                engineVersion));
    }

    public String canonicalJson() {
        return CanonicalJson.write(canonicalValue());
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("assignmentRevision", assignmentRevision);
        value.put("assignments", assignments.stream().map(AssignmentFact::canonicalValue).toList());
        value.put("bases", bases.stream().map(BasisFact::canonicalValue).toList());
        value.put("basisRevision", basisRevision);
        value.put("caseId", caseId);
        value.put("checklistSnapshotDigest", checklistSnapshotDigest);
        value.put("checklistSnapshotId", checklistSnapshotId);
        value.put("contractVersion", contractVersion);
        value.put("diagnostics", diagnostics.stream().map(DiagnosticFact::canonicalValue).toList());
        value.put("engineVersion", engineVersion);
        value.put("materials", materials.stream().map(MaterialFact::canonicalValue).toList());
        value.put("materialSetRevision", materialSetRevision);
        value.put("nameRevision", nameRevision);
        value.put("sources", sources.stream().map(SourceFact::canonicalValue).toList());
        value.put("uploadConfirmationId", uploadConfirmationId);
        value.put("uploadMaterialSetDigest", uploadMaterialSetDigest);
        return value;
    }

    public static ReviewInputManifest readCanonical(String json) {
        try {
            ReviewInputManifest manifest = READER.readValue(json, ReviewInputManifest.class);
            if (!manifest.canonicalJson().equals(json)) {
                throw invalidManifest();
            }
            return manifest;
        } catch (ReviewM0Exception error) {
            throw error;
        } catch (Exception error) {
            throw invalidManifest();
        }
    }

    public record BasisFact(String basisId, OfficialBasisRole role, String fileDigest) {
        public BasisFact {
            requireText(basisId, "basisId");
            if (role == null) throw invalidManifest();
            requireDigest(fileDigest, "fileDigest");
        }

        public String digest() { return CanonicalJson.sha256(canonicalValue()); }

        public Map<String, Object> canonicalValue() {
            return map("basisId", basisId, "fileDigest", fileDigest, "role", role);
        }
    }

    public record SourceFact(
            String sourceId, FormalFileType formalFileType, String fileDigest, long fileSizeBytes
    ) {
        public SourceFact {
            requireText(sourceId, "sourceId");
            if (formalFileType == null || fileSizeBytes < 0) throw invalidManifest();
            requireDigest(fileDigest, "fileDigest");
        }

        public String digest() { return CanonicalJson.sha256(canonicalValue()); }

        public Map<String, Object> canonicalValue() {
            return map("fileDigest", fileDigest, "fileSizeBytes", fileSizeBytes,
                    "formalFileType", formalFileType, "sourceId", sourceId);
        }
    }

    public record MaterialFact(
            String materialId,
            String sourceId,
            MaterialKind kind,
            String normalizedEntryPath,
            Integer entryIndex,
            String contentDigest,
            FormalFileType formalFileType,
            String submissionFileName,
            MaterialStatus status,
            CannotConfirmReason cannotConfirmReason,
            String failureStage
    ) {
        public MaterialFact {
            requireText(materialId, "materialId");
            requireText(sourceId, "sourceId");
            if (kind == null || formalFileType == null || status == null) throw invalidManifest();
            requireDigest(contentDigest, "contentDigest");
            requireText(submissionFileName, "submissionFileName");
            if ((status == MaterialStatus.CANNOT_CONFIRM) != (cannotConfirmReason != null)) {
                throw invalidManifest();
            }
        }

        public String digest() { return CanonicalJson.sha256(canonicalValueWithoutName()); }

        public Map<String, Object> canonicalValueWithoutName() {
            return map(
                    "cannotConfirmReason", cannotConfirmReason,
                    "contentDigest", contentDigest,
                    "entryIndex", entryIndex,
                    "failureStage", failureStage,
                    "formalFileType", formalFileType,
                    "kind", kind,
                    "materialId", materialId,
                    "normalizedEntryPath", normalizedEntryPath,
                    "sourceId", sourceId,
                    "status", status);
        }

        public Map<String, Object> canonicalValue() {
            Map<String, Object> value = new LinkedHashMap<>(canonicalValueWithoutName());
            value.put("submissionFileName", submissionFileName);
            return value;
        }
    }

    public record AssignmentFact(String materialId, String requirementId, AssignmentStatus status) {
        public AssignmentFact {
            requireText(materialId, "materialId");
            requireText(requirementId, "requirementId");
            if (status != AssignmentStatus.USER_CONFIRMED) throw invalidManifest();
        }

        public String stableId() { return materialId + ":" + requirementId; }

        public Map<String, Object> canonicalValue() {
            return map("materialId", materialId, "requirementId", requirementId, "status", status);
        }
    }

    public record DiagnosticFact(
            String diagnosticId,
            DiagnosticSubjectType subjectType,
            String subjectId,
            CannotConfirmReason reason,
            String failureStage,
            String locatorDigest
    ) {
        public DiagnosticFact {
            requireText(diagnosticId, "diagnosticId");
            requireText(subjectId, "subjectId");
            if (subjectType == null || reason == null || subjectType == DiagnosticSubjectType.OFFICIAL_BASIS) {
                throw invalidManifest();
            }
            if (locatorDigest != null) requireDigest(locatorDigest, "locatorDigest");
        }

        public String stableId() { return subjectType + ":" + subjectId + ":" + reason + ":" + diagnosticId; }

        public String digest() { return CanonicalJson.sha256(canonicalValue()); }

        public Map<String, Object> canonicalValue() {
            return map(
                    "diagnosticId", diagnosticId,
                    "failureStage", failureStage,
                    "locatorDigest", locatorDigest,
                    "reason", reason,
                    "subjectId", subjectId,
                    "subjectType", subjectType);
        }
    }

    private static <T> List<T> sorted(List<T> values, Comparator<T> comparator) {
        if (values == null) throw invalidManifest();
        return List.copyOf(values).stream().sorted(comparator).toList();
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            value.put((String) entries[index], entries[index + 1]);
        }
        return value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw invalidManifest();
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalidManifest();
    }

    private static ReviewM0Exception invalidManifest() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                "Review input manifest is invalid");
    }
}
