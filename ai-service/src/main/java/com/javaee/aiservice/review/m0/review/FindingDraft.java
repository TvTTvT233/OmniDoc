package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.evidence.EvidenceDraft;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FindingType;

import java.util.Comparator;
import java.util.List;

public record FindingDraft(
        FindingType type,
        CannotConfirmReason cannotConfirmReason,
        String requirementId,
        String materialId,
        String stableFindingKey,
        String message,
        List<EvidenceDraft> evidence
) {
    public FindingDraft {
        evidence = List.copyOf(evidence).stream()
                .sorted(Comparator.comparing(EvidenceDraft::normalizedKey))
                .toList();
    }

    public String normalizedKey() {
        return stableFindingKey + ":" + type + ":" + evidence.stream()
                .map(EvidenceDraft::normalizedKey).reduce("", (left, right) -> left + "|" + right);
    }
}
