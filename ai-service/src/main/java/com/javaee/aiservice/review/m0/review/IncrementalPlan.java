package com.javaee.aiservice.review.m0.review;

import java.util.Set;

public record IncrementalPlan(Set<String> changedMaterialIds, boolean nameOnly) {
    public IncrementalPlan {
        changedMaterialIds = Set.copyOf(changedMaterialIds);
    }
}
