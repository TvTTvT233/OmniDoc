package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Generates deterministic collection names from the complete vector-space specification. */
public final class QdrantCollectionNameFactory {

    private static final int MAX_MODEL_COMPONENT_LENGTH = 160;
    private final String prefix;

    public QdrantCollectionNameFactory() {
        this("omnidoc_chunks_");
    }

    /** Test-only prefixes must start with {@code test_}. */
    QdrantCollectionNameFactory(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("collection prefix must not be blank");
        }
        this.prefix = prefix;
    }

    public String create(EmbeddingSpec embedding) {
        if (embedding == null) {
            throw new IllegalArgumentException("embedding must not be null");
        }
        String normalized = embedding.model().trim().toLowerCase(Locale.ROOT);
        String sanitized = normalized
                .replaceAll("[^a-z0-9-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.isBlank()) {
            sanitized = "model";
        }
        boolean transformed = !sanitized.equals(embedding.model().trim());
        if (sanitized.length() > MAX_MODEL_COMPONENT_LENGTH) {
            sanitized = sanitized.substring(0, MAX_MODEL_COMPONENT_LENGTH);
            transformed = true;
        }
        if (transformed) {
            sanitized = sanitized + "_" + shortHash(embedding.model());
        }
        return prefix + sanitized + "_" + embedding.dimension() + "_v" + embedding.schemaVersion();
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for collection naming", exception);
        }
    }
}
