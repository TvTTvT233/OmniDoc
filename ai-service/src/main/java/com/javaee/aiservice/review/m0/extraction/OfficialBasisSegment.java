package com.javaee.aiservice.review.m0.extraction;

import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

public record OfficialBasisSegment(
        String basisId,
        OfficialBasisSourceLocator sourceLocator,
        String text
) {
    public OfficialBasisSegment {
        if (basisId == null || basisId.isBlank() || basisId.length() > 64
                || sourceLocator == null || !sourceLocator.hasReviewableLocation()
                || text == null || text.isBlank()) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID,
                    "Official basis segment is not reliably located");
        }
    }
}
