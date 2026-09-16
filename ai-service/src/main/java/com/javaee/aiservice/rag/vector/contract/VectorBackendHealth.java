package com.javaee.aiservice.rag.vector.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sanitized health information; details must never contain credentials or vector payloads.
 */
public record VectorBackendHealth(
        VectorBackendType backend,
        boolean available,
        String message,
        Map<String, Object> details
) {

    public VectorBackendHealth {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        message = message == null ? "" : message;
        details = details == null || details.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
