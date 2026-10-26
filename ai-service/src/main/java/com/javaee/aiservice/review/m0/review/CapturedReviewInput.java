package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot;

public record CapturedReviewInput(
        FormalChecklist checklist,
        MaterialCollectionSnapshot materials,
        ReviewInputManifest manifest
) {
}
