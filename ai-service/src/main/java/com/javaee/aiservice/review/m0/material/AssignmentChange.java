package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.model.AssignmentStatus;

public record AssignmentChange(String materialId, String requirementId, AssignmentStatus status) {
    public AssignmentChange {
        if (materialId == null || materialId.isBlank() || materialId.length() > 64
                || requirementId == null || requirementId.isBlank() || requirementId.length() > 64
                || status == null) {
            throw MaterialErrors.invalid("Assignment change is invalid");
        }
    }
}
