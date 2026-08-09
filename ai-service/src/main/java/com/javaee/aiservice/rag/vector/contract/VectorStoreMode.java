package com.javaee.aiservice.rag.vector.contract;

import java.util.Arrays;

/**
 * Runtime routing mode. Shadow mode must always return the baseline response.
 */
public enum VectorStoreMode {
    BASELINE("baseline"),
    SHADOW("shadow"),
    QDRANT("qdrant");

    private final String configValue;

    VectorStoreMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static VectorStoreMode fromConfig(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.configValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported vector store mode: " + value));
    }
}
