package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class IncrementalReviewValidator {
    public IncrementalPlan validate(
            ReviewInputManifest base,
            ReviewInputManifest current,
            List<String> declaredChangedMaterialIds
    ) {
        if (!base.contractVersion().equals(current.contractVersion())
                || !base.engineVersion().equals(current.engineVersion())
                || !base.caseId().equals(current.caseId())
                || base.basisRevision() != current.basisRevision()
                || !base.bases().equals(current.bases())
                || !base.checklistSnapshotId().equals(current.checklistSnapshotId())
                || !base.checklistSnapshotDigest().equals(current.checklistSnapshotDigest())) {
            throw mismatch("Incremental base has a different checklist, basis, contract, or engine");
        }

        Set<String> actual = new HashSet<>();
        Map<String, ReviewInputManifest.MaterialFact> oldMaterials = byId(
                base.materials(), ReviewInputManifest.MaterialFact::materialId);
        Map<String, ReviewInputManifest.MaterialFact> newMaterials = byId(
                current.materials(), ReviewInputManifest.MaterialFact::materialId);
        union(oldMaterials.keySet(), newMaterials.keySet()).forEach(id -> {
            if (!Objects.equals(oldMaterials.get(id), newMaterials.get(id))) actual.add(id);
        });

        Map<String, ReviewInputManifest.AssignmentFact> oldAssignments = byId(
                base.assignments(), ReviewInputManifest.AssignmentFact::stableId);
        Map<String, ReviewInputManifest.AssignmentFact> newAssignments = byId(
                current.assignments(), ReviewInputManifest.AssignmentFact::stableId);
        union(oldAssignments.keySet(), newAssignments.keySet()).forEach(id -> {
            ReviewInputManifest.AssignmentFact oldValue = oldAssignments.get(id);
            ReviewInputManifest.AssignmentFact newValue = newAssignments.get(id);
            if (!Objects.equals(oldValue, newValue)) {
                actual.add((oldValue != null ? oldValue : newValue).materialId());
            }
        });

        boolean unmappableChange = addSourceChanges(base, current, actual)
                | addDiagnosticChanges(base, current, actual);
        Set<String> declared = Set.copyOf(declaredChangedMaterialIds);
        if (unmappableChange || actual.isEmpty() || !actual.equals(declared)) {
            throw mismatch("Incremental changedMaterialIds do not exactly describe the input change");
        }

        boolean nameOnly = base.materialSetRevision() == current.materialSetRevision()
                && base.assignmentRevision() == current.assignmentRevision()
                && base.sources().equals(current.sources())
                && base.assignments().equals(current.assignments())
                && base.diagnostics().equals(current.diagnostics())
                && base.uploadConfirmationId().equals(current.uploadConfirmationId())
                && base.uploadMaterialSetDigest().equals(current.uploadMaterialSetDigest())
                && actual.stream().allMatch(id -> sameExceptName(oldMaterials.get(id), newMaterials.get(id)));
        if (nameOnly && base.nameRevision() == current.nameRevision()) {
            throw mismatch("Incremental name change did not advance nameRevision");
        }
        if (!nameOnly && base.materialSetRevision() == current.materialSetRevision()
                && base.assignmentRevision() == current.assignmentRevision()
                && base.nameRevision() == current.nameRevision()) {
            throw mismatch("Incremental input changed without a matching frozen revision");
        }
        if (base.materialSetRevision() != current.materialSetRevision()
                && base.uploadConfirmationId().equals(current.uploadConfirmationId())) {
            throw mismatch("Changed material bytes require a new upload confirmation");
        }
        return new IncrementalPlan(actual, nameOnly);
    }

    private static boolean addSourceChanges(
            ReviewInputManifest base,
            ReviewInputManifest current,
            Set<String> actual
    ) {
        Map<String, ReviewInputManifest.SourceFact> oldSources = byId(
                base.sources(), ReviewInputManifest.SourceFact::sourceId);
        Map<String, ReviewInputManifest.SourceFact> newSources = byId(
                current.sources(), ReviewInputManifest.SourceFact::sourceId);
        boolean unmappable = false;
        for (String sourceId : union(oldSources.keySet(), newSources.keySet())) {
            if (Objects.equals(oldSources.get(sourceId), newSources.get(sourceId))) continue;
            Set<String> linked = new HashSet<>();
            base.materials().stream().filter(item -> item.sourceId().equals(sourceId))
                    .map(ReviewInputManifest.MaterialFact::materialId).forEach(linked::add);
            current.materials().stream().filter(item -> item.sourceId().equals(sourceId))
                    .map(ReviewInputManifest.MaterialFact::materialId).forEach(linked::add);
            if (linked.isEmpty()) unmappable = true;
            actual.addAll(linked);
        }
        return unmappable;
    }

    private static boolean addDiagnosticChanges(
            ReviewInputManifest base,
            ReviewInputManifest current,
            Set<String> actual
    ) {
        Map<String, ReviewInputManifest.DiagnosticFact> oldDiagnostics = canonicalDiagnostics(base.diagnostics());
        Map<String, ReviewInputManifest.DiagnosticFact> newDiagnostics = canonicalDiagnostics(current.diagnostics());
        boolean unmappable = false;
        for (String key : union(oldDiagnostics.keySet(), newDiagnostics.keySet())) {
            ReviewInputManifest.DiagnosticFact oldValue = oldDiagnostics.get(key);
            ReviewInputManifest.DiagnosticFact newValue = newDiagnostics.get(key);
            if (Objects.equals(oldValue, newValue)) continue;
            ReviewInputManifest.DiagnosticFact diagnostic = oldValue != null ? oldValue : newValue;
            if (diagnostic.subjectType() == com.javaee.aiservice.review.m0.model.DiagnosticSubjectType.MATERIAL) {
                actual.add(diagnostic.subjectId());
            } else {
                Set<String> linked = new HashSet<>();
                base.materials().stream().filter(item -> item.sourceId().equals(diagnostic.subjectId()))
                        .map(ReviewInputManifest.MaterialFact::materialId).forEach(linked::add);
                current.materials().stream().filter(item -> item.sourceId().equals(diagnostic.subjectId()))
                        .map(ReviewInputManifest.MaterialFact::materialId).forEach(linked::add);
                if (linked.isEmpty()) unmappable = true;
                actual.addAll(linked);
            }
        }
        return unmappable;
    }

    private static Map<String, ReviewInputManifest.DiagnosticFact> canonicalDiagnostics(
            List<ReviewInputManifest.DiagnosticFact> values
    ) {
        return values.stream().collect(Collectors.toMap(
                item -> CanonicalJson.write(item.canonicalValue()), Function.identity()));
    }

    private static boolean sameExceptName(
            ReviewInputManifest.MaterialFact oldValue,
            ReviewInputManifest.MaterialFact newValue
    ) {
        return oldValue != null && newValue != null
                && oldValue.canonicalValueWithoutName().equals(newValue.canonicalValueWithoutName())
                && !oldValue.submissionFileName().equals(newValue.submissionFileName());
    }

    private static <T> Map<String, T> byId(List<T> values, Function<T, String> id) {
        Map<String, T> result = new HashMap<>();
        for (T value : values) {
            if (result.put(id.apply(value), value) != null) throw mismatch("Review manifest contains duplicates");
        }
        return result;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union;
    }

    private static ReviewM0Exception mismatch(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_INCREMENTAL_BASE_MISMATCH, message);
    }
}
