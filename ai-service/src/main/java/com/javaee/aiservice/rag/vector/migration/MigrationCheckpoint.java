package com.javaee.aiservice.rag.vector.migration;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;

/**
 * Versioned checkpoint persisted only after a complete target batch succeeds.
 */
public record MigrationCheckpoint(
        int formatVersion,
        String migrationId,
        String cursor,
        long pagesCompleted,
        long sourceRecords,
        long successfulRecords,
        boolean complete,
        EmbeddingSpec assumedEmbedding,
        long updatedAtEpochMillis
) {

    public static final int CURRENT_FORMAT_VERSION = 1;

    public MigrationCheckpoint {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported migration checkpoint format");
        }
        if (migrationId == null || migrationId.isBlank()) {
            throw new IllegalArgumentException("migrationId must not be blank");
        }
        cursor = cursor == null ? "" : cursor;
        if (!complete && pagesCompleted > 0L && cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank for an incomplete checkpoint");
        }
        if (pagesCompleted < 0L || sourceRecords < 0L || successfulRecords < 0L) {
            throw new IllegalArgumentException("checkpoint counts must not be negative");
        }
        if (sourceRecords != successfulRecords) {
            throw new IllegalArgumentException("checkpoint may contain only confirmed successful records");
        }
        if (assumedEmbedding == null) {
            throw new IllegalArgumentException("assumedEmbedding must not be null");
        }
        if (updatedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("updatedAtEpochMillis must not be negative");
        }
    }

    public static MigrationCheckpoint initial(MigrationOptions options, long nowEpochMillis) {
        return new MigrationCheckpoint(
                CURRENT_FORMAT_VERSION,
                options.migrationId(),
                "",
                0L,
                0L,
                0L,
                false,
                options.assumedEmbedding(),
                nowEpochMillis
        );
    }

    public MigrationCheckpoint advance(
            String nextCursor,
            int confirmedRecords,
            boolean pageComplete,
            long nowEpochMillis
    ) {
        if (confirmedRecords < 0) {
            throw new IllegalArgumentException("confirmedRecords must not be negative");
        }
        return new MigrationCheckpoint(
                CURRENT_FORMAT_VERSION,
                migrationId,
                nextCursor,
                Math.addExact(pagesCompleted, 1L),
                Math.addExact(sourceRecords, confirmedRecords),
                Math.addExact(successfulRecords, confirmedRecords),
                pageComplete,
                assumedEmbedding,
                nowEpochMillis
        );
    }
}
