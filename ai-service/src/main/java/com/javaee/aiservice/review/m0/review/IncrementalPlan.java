package com.javaee.aiservice.review.m0.review;

import java.util.Set;

public record IncrementalPlan(
        Set<String> changedMaterialIds,
        boolean nameOnly,
        boolean recomputeCompleteness
) {
    public IncrementalPlan {
        changedMaterialIds = Set.copyOf(changedMaterialIds);
        if (recomputeCompleteness && !nameOnly) {
            throw new IllegalArgumentException(
                    "The explicit completeness expansion is only meaningful for a name-only input change");
        }
    }
}
