package com.javaee.aiservice.review.m0.extraction;

import com.javaee.aiservice.review.m0.checklist.ChecklistCandidate;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.util.List;

public record RemoteExtractionResult(
        String providerRequestId,
        byte[] responseBytes,
        List<ChecklistCandidate> candidates
) {
    public RemoteExtractionResult {
        if (responseBytes == null) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_MODEL_FAILED,
                    "Remote extraction response is invalid");
        }
        responseBytes = responseBytes.clone();
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    @Override
    public byte[] responseBytes() {
        return responseBytes.clone();
    }
}
