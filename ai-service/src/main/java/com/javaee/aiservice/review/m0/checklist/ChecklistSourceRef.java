package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.util.LinkedHashMap;
import java.util.Map;

public record ChecklistSourceRef(
        String basisId,
        OfficialBasisSourceLocator sourceLocator,
        String quotedText
) {
    private static final int MAX_QUOTED_TEXT_LENGTH = 2048;

    public ChecklistSourceRef {
        if (basisId == null || basisId.isBlank() || basisId.length() > 64) {
            throw invalid("Official source reference requires a basisId");
        }
        if (sourceLocator == null || !sourceLocator.hasReviewableLocation()) {
            throw invalid("Official source reference requires a reviewable sourceLocator");
        }
        if (quotedText == null || quotedText.isBlank() || quotedText.length() > MAX_QUOTED_TEXT_LENGTH) {
            throw invalid("Official source reference requires a bounded quotedText locator excerpt");
        }
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("entryPath", sourceLocator.entryPath());
        locator.put("page", sourceLocator.page());
        locator.put("sheet", sourceLocator.sheet());
        locator.put("cellRange", sourceLocator.cellRange());
        locator.put("block", sourceLocator.block());
        locator.put("entryDigest", sourceLocator.entryDigest());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("basisId", basisId);
        value.put("sourceLocator", locator);
        value.put("quotedText", quotedText);
        return value;
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }
}
