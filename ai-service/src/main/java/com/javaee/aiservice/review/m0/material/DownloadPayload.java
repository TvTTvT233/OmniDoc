package com.javaee.aiservice.review.m0.material;

import java.io.InputStream;

public record DownloadPayload(
        String materialId,
        String contentType,
        long contentLength,
        String contentDisposition,
        InputStream stream
) {
}
