package com.javaee.aiservice.rag.vector.contract;

import java.util.Arrays;

/**
 * Provider-neutral identifiers for the vector backends supported by OmniDoc.
 */
public enum VectorBackendType {
    LOCAL_REDIS_HNSW("local-redis-hnsw"),
    QDRANT("qdrant");

    private final String configValue;

    VectorBackendType(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static VectorBackendType fromConfig(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.configValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported vector backend: " + value));
    }
}
