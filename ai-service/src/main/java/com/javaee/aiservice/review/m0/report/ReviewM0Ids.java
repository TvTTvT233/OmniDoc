package com.javaee.aiservice.review.m0.report;

import java.util.UUID;

public final class ReviewM0Ids {
    private ReviewM0Ids() {
    }

    public static String next(String prefix) {
        return prefix + UUID.randomUUID();
    }
}
