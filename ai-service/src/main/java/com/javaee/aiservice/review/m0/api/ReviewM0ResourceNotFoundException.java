package com.javaee.aiservice.review.m0.api;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

public final class ReviewM0ResourceNotFoundException extends ReviewM0Exception {
    private final String resourceType;

    public ReviewM0ResourceNotFoundException(String resourceType) {
        super(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND, "Review M0 resource was not found");
        this.resourceType = resourceType;
    }

    public String resourceType() {
        return resourceType;
    }
}
