package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.OfficialBasisRole;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

public record OfficialBasisAttachment(
        String basisId,
        String fileId,
        OfficialBasisRole role,
        String originalFileNameSnapshot,
        String declaredContentType,
        long fileSizeBytes,
        String fileDigest,
        String storageVersion
) {
    public OfficialBasisAttachment {
        if (basisId == null || basisId.isBlank() || basisId.length() > 64
                || fileId == null || fileId.isBlank() || fileId.length() > 64
                || role == null
                || originalFileNameSnapshot == null || originalFileNameSnapshot.isBlank()
                || originalFileNameSnapshot.length() > 1024
                || fileSizeBytes < 0
                || fileDigest == null || !fileDigest.matches("[0-9a-f]{64}")) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID,
                    "Official basis attachment metadata is invalid");
        }
    }
}
