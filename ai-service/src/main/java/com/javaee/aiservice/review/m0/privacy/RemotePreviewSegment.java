package com.javaee.aiservice.review.m0.privacy;

import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.util.LinkedHashMap;
import java.util.Map;

public record RemotePreviewSegment(
        String segmentId,
        String basisId,
        OfficialBasisSourceLocator sourceLocator,
        boolean included,
        String redactedText
) {
    private static final int MAX_REDACTED_TEXT_LENGTH = 65_536;

    public RemotePreviewSegment {
        if (segmentId == null || segmentId.isBlank() || segmentId.length() > 64
                || basisId == null || basisId.isBlank() || basisId.length() > 64
                || sourceLocator == null || !sourceLocator.hasReviewableLocation()
                || redactedText == null || redactedText.length() > MAX_REDACTED_TEXT_LENGTH) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID,
                    "Remote preview segment is invalid");
        }
    }

    Map<String, Object> storageValue() {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("entryPath", sourceLocator.entryPath());
        locator.put("page", sourceLocator.page());
        locator.put("sheet", sourceLocator.sheet());
        locator.put("cellRange", sourceLocator.cellRange());
        locator.put("block", sourceLocator.block());
        locator.put("entryDigest", sourceLocator.entryDigest());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("segmentId", segmentId);
        value.put("basisId", basisId);
        value.put("sourceLocator", locator);
        value.put("included", included);
        value.put("redactedText", redactedText);
        return value;
    }
}
