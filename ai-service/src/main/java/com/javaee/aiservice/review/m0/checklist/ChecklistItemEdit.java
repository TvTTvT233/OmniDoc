package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;

public record ChecklistItemEdit(
        long expectedItemVersion,
        String displayName,
        boolean enabled,
        RequirementApplicability applicability,
        int minimumCount,
        FileNameRule fileNameRule
) {
}
