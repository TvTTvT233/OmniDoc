package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;

import java.util.List;

public record ChecklistCandidate(
        String displayName,
        boolean enabled,
        RequirementApplicability applicability,
        int minimumCount,
        FileNameRule fileNameRule,
        List<ChecklistSourceRef> sourceRefs
) {
    public ChecklistCandidate {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
