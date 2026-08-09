package com.javaee.aiservice.rag.vector.acceptance;

import java.util.Locale;
import java.util.Set;

/**
 * Destructive acceptance operations are allowed only for names under this prefix.
 */
public final class Qv04CollectionBoundary {

    public static final String REQUIRED_PREFIX = "qv04_test_";
    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "omnidoc_chunks_active",
            "omnidoc_chunks",
            "production",
            "prod");

    private Qv04CollectionBoundary() {
    }

    public static String requireTestName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("test Collection name must not be blank");
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_NAMES.contains(normalized)) {
            throw new IllegalArgumentException("refusing non-test Collection name: " + name);
        }
        if (!normalized.startsWith(REQUIRED_PREFIX)) {
            throw new IllegalArgumentException(
                    "test Collection name must start with " + REQUIRED_PREFIX + ": " + name);
        }
        if (!normalized.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("test Collection name contains unsafe characters: " + name);
        }
        return normalized;
    }
}
