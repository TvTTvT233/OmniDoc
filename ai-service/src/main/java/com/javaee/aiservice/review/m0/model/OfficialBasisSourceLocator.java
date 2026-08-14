package com.javaee.aiservice.review.m0.model;

public record OfficialBasisSourceLocator(
        String entryPath,
        Integer page,
        String sheet,
        String cellRange,
        String block,
        String entryDigest
) {
    public OfficialBasisSourceLocator {
        if ((entryPath == null) != (entryDigest == null)) {
            throw ModelChecks.invalid("ZIP basis locators require both entryPath and entryDigest");
        }
        if (entryDigest != null) {
            ModelChecks.digest(entryDigest, "entryDigest");
        }
        if (page != null && page < 1) {
            throw ModelChecks.invalid("page must be positive");
        }
    }

    public boolean hasReviewableLocation() {
        return entryPath != null || page != null || sheet != null || block != null;
    }
}
