package com.javaee.aiservice.rag.vector.migration;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;

import java.util.regex.Pattern;

/**
 * Controlled migration inputs. Defaults are intentionally non-mutating.
 */
public record MigrationOptions(
        String migrationId,
        boolean dryRun,
        int batchSize,
        EmbeddingSpec assumedEmbedding
) {

    public static final int DEFAULT_BATCH_SIZE = 100;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public MigrationOptions {
        if (migrationId == null || !SAFE_ID.matcher(migrationId).matches()) {
            throw new IllegalArgumentException("migrationId must contain only letters, digits, '.', '_' or '-'");
        }
        if (batchSize <= 0 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        if (assumedEmbedding == null) {
            throw new IllegalArgumentException("assumedEmbedding must not be null");
        }
    }

    public static MigrationOptions defaults(String migrationId, EmbeddingSpec assumedEmbedding) {
        return new MigrationOptions(migrationId, true, DEFAULT_BATCH_SIZE, assumedEmbedding);
    }
}
