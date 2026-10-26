package com.javaee.aiservice.review.m0.model;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReviewInputFingerprint(String canonicalJson, String sha256) {
    public static ReviewInputFingerprint calculate(ReviewInputFingerprintSpec input) {
        Comparator<String> order = UnicodeCodePointComparator.INSTANCE;
        List<Map<String, Object>> bases = input.basisDigests().stream()
                .sorted(Comparator.comparing(DigestEntry::stableId, order))
                .map(entry -> orderedMap("stableId", entry.stableId(), "digest", entry.digest()))
                .toList();
        List<Map<String, Object>> materials = input.materialDigests().stream()
                .sorted(Comparator.comparing(DigestEntry::stableId, order))
                .map(entry -> orderedMap("stableId", entry.stableId(), "digest", entry.digest()))
                .toList();
        List<Map<String, Object>> assignments = input.confirmedAssignments().stream()
                .sorted(Comparator.comparing(ConfirmedAssignmentFingerprint::stableId, order))
                .map(entry -> orderedMap("materialId", entry.materialId(), "requirementId", entry.requirementId()))
                .toList();
        List<Map<String, Object>> names = input.logicalFileNames().stream()
                .sorted(Comparator.comparing(LogicalNameFingerprint::materialId, order))
                .map(entry -> orderedMap(
                        "materialId", entry.materialId(), "submissionFileName", entry.submissionFileName()))
                .toList();

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("contractVersion", input.contractVersion());
        canonical.put("caseId", input.caseId());
        canonical.put("basisRevision", input.basisRevision());
        canonical.put("basisDigests", bases);
        canonical.put("checklistSnapshotId", input.checklistSnapshotId());
        canonical.put("checklistSnapshotDigest", input.checklistSnapshotDigest());
        canonical.put("materialSetRevision", input.materialSetRevision());
        canonical.put("materialDigests", materials);
        canonical.put("assignmentRevision", input.assignmentRevision());
        canonical.put("confirmedAssignments", assignments);
        canonical.put("nameRevision", input.nameRevision());
        canonical.put("logicalFileNames", names);
        canonical.put("uploadConfirmationId", input.uploadConfirmationId());
        canonical.put("engineVersion", input.engineVersion());
        String json = CanonicalJson.write(canonical);
        return new ReviewInputFingerprint(json, CanonicalJson.sha256(canonical));
    }

    private static Map<String, Object> orderedMap(Object... fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            values.put((String) fields[index], fields[index + 1]);
        }
        return values;
    }
}
