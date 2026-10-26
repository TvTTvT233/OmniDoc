package com.javaee.aiservice.review.m0.material;

import java.io.InputStream;

public record DownloadPayload(
        String materialId,
        String contentType,
        long contentLength,
        String contentDisposition,
        String contentDigest,
        InputStream stream
) {
    public DownloadPayload {
        if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest must be lower-case SHA-256 hex");
        }
    }
}
