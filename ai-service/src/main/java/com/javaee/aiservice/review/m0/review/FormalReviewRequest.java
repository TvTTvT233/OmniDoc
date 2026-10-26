package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewMode;
import com.javaee.aiservice.review.m0.model.UnicodeCodePointComparator;

import java.util.HashSet;
import java.util.List;

public record FormalReviewRequest(
        ReviewMode mode,
        String checklistSnapshotId,
        String uploadConfirmationId,
        String baseReportId,
        List<String> changedMaterialIds
) {
    public FormalReviewRequest {
        if (mode == null || checklistSnapshotId == null || checklistSnapshotId.isBlank()
                || uploadConfirmationId == null || uploadConfirmationId.isBlank()) {
            throw invalid("Formal review request is incomplete");
        }
        changedMaterialIds = changedMaterialIds == null ? List.of() : List.copyOf(changedMaterialIds);
        if (changedMaterialIds.stream().anyMatch(item -> item == null || item.isBlank())
                || new HashSet<>(changedMaterialIds).size() != changedMaterialIds.size()) {
            throw invalid("changedMaterialIds must be a unique set of stable material IDs");
        }
        changedMaterialIds = changedMaterialIds.stream()
                .sorted(UnicodeCodePointComparator.INSTANCE).toList();
        if (mode == ReviewMode.FULL && (baseReportId != null || !changedMaterialIds.isEmpty())) {
            throw invalid("FULL review cannot declare an incremental base or changed materials");
        }
        if (mode == ReviewMode.INCREMENTAL
                && (baseReportId == null || baseReportId.isBlank() || changedMaterialIds.isEmpty())) {
            throw invalid("INCREMENTAL review requires a base report and changed materials");
        }
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }
}
