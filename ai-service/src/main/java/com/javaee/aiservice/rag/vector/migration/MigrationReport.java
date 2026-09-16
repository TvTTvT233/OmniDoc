package com.javaee.aiservice.rag.vector.migration;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;

/**
 * Count-only migration result. It deliberately excludes record data and raw checkpoint cursors.
 */
public record MigrationReport(
        String migrationId,
        MigrationStatus status,
        boolean dryRun,
        long pagesProcessed,
        long sourceRecords,
        long successfulRecords,
        long failedRecords,
        long plannedRecords,
        long qdrantRecords,
        EmbeddingSpec assumedEmbedding
) {

    public MigrationReport {
        if (migrationId == null || migrationId.isBlank()) {
            throw new IllegalArgumentException("migrationId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (pagesProcessed < 0L || sourceRecords < 0L || successfulRecords < 0L
                || failedRecords < 0L || plannedRecords < 0L || qdrantRecords < -1L) {
            throw new IllegalArgumentException("migration counts must not be negative");
        }
        if (assumedEmbedding == null) {
            throw new IllegalArgumentException("assumedEmbedding must not be null");
        }
        if (sourceRecords != successfulRecords + failedRecords + plannedRecords) {
            throw new IllegalArgumentException("sourceRecords must equal successful + failed + planned");
        }
    }

    public boolean countVerified() {
        return qdrantRecords >= 0L;
    }

    public boolean countsMatch() {
        return countVerified() && qdrantRecords == successfulRecords;
    }
}
