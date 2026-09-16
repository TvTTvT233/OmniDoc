package com.javaee.aiservice.rag.vector.migration;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorExportRequest;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorRecordExporter;
import com.javaee.aiservice.rag.vector.contract.VectorRecordPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal, checkpointed migration from the baseline exporter to idempotent Qdrant batch upserts.
 * This service is deliberately not exposed through a public controller.
 */
public final class VectorMigrationService {

    private static final Logger log = LoggerFactory.getLogger(VectorMigrationService.class);

    private final VectorRecordExporter exporter;
    private final VectorBackend qdrant;
    private final MigrationCheckpointStore checkpointStore;
    private final MigrationTargetCounter targetCounter;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public VectorMigrationService(
            VectorRecordExporter exporter,
            VectorBackend qdrant,
            MigrationCheckpointStore checkpointStore,
            MigrationTargetCounter targetCounter
    ) {
        this(exporter, qdrant, checkpointStore, targetCounter, Clock.systemUTC());
    }

    public VectorMigrationService(
            VectorRecordExporter exporter,
            VectorBackend qdrant,
            MigrationCheckpointStore checkpointStore,
            MigrationTargetCounter targetCounter,
            Clock clock
    ) {
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
        this.qdrant = Objects.requireNonNull(qdrant, "qdrant must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
        this.targetCounter = Objects.requireNonNull(targetCounter, "targetCounter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (qdrant.type() != VectorBackendType.QDRANT) {
            throw new VectorBackendException(
                    VectorBackendType.QDRANT,
                    VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                    "Migration target must be qdrant",
                    false
            );
        }
    }

    public MigrationReport migrate(MigrationOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        if (!running.compareAndSet(false, true)) {
            throw failure(
                    "Another vector migration is already running",
                    true,
                    null,
                    report(options, MigrationStatus.FAILED, 0L, 0L, 0L, 0L, 0L, -1L)
            );
        }
        try {
            return options.dryRun() ? dryRun(options) : migrateWithCheckpoint(options);
        } finally {
            running.set(false);
        }
    }

    private MigrationReport dryRun(MigrationOptions options) {
        String cursor = "";
        Set<String> visitedCursors = new HashSet<>();
        long pages = 0L;
        long source = 0L;

        while (true) {
            VectorRecordPage page;
            try {
                page = export(cursor, options);
            } catch (RuntimeException exception) {
                throw failure(
                        "Unable to export a migration page",
                        true,
                        exception,
                        report(options, MigrationStatus.FAILED, pages, source, 0L, 0L, source, -1L)
                );
            }

            long pageSize = page.records().size();
            try {
                validateEmbedding(page.records(), options.assumedEmbedding());
                validateNextCursor(cursor, page, visitedCursors);
                pages = Math.addExact(pages, 1L);
                source = Math.addExact(source, pageSize);
            } catch (RuntimeException exception) {
                long total = safeAdd(source, pageSize);
                throw failure(
                        "Migration page is incompatible with the configured embedding or pagination",
                        false,
                        exception,
                        report(options, MigrationStatus.FAILED, pages, total, 0L, 0L, total, -1L)
                );
            }

            if (page.complete()) {
                MigrationReport report = report(
                        options,
                        MigrationStatus.DRY_RUN_COMPLETE,
                        pages,
                        source,
                        0L,
                        0L,
                        source,
                        -1L
                );
                logReport(report);
                return report;
            }
            cursor = page.nextCursor();
        }
    }

    private MigrationReport migrateWithCheckpoint(MigrationOptions options) {
        MigrationCheckpoint checkpoint = loadCheckpoint(options);
        if (checkpoint.complete()) {
            return verifyCompletedCheckpoint(options, checkpoint);
        }

        String cursor = checkpoint.cursor();
        Set<String> visitedCursors = new HashSet<>();
        while (true) {
            VectorRecordPage page;
            try {
                page = export(cursor, options);
            } catch (RuntimeException exception) {
                throw failure(
                        "Unable to export a migration page",
                        true,
                        exception,
                        fromCheckpoint(options, MigrationStatus.FAILED, checkpoint, 0L, -1L)
                );
            }

            int pageSize = page.records().size();
            try {
                validateEmbedding(page.records(), options.assumedEmbedding());
                validateNextCursor(cursor, page, visitedCursors);
            } catch (RuntimeException exception) {
                throw failure(
                        "Migration page is incompatible with the configured embedding or pagination",
                        false,
                        exception,
                        fromCheckpoint(options, MigrationStatus.FAILED, checkpoint, pageSize, -1L)
                );
            }

            try {
                if (!page.records().isEmpty()) {
                    qdrant.upsertBatch(page.records());
                }
                MigrationCheckpoint advanced = checkpoint.advance(
                        page.nextCursor(),
                        pageSize,
                        page.complete(),
                        clock.millis()
                );
                checkpointStore.save(advanced);
                checkpoint = advanced;
            } catch (RuntimeException exception) {
                throw failure(
                        "Qdrant migration batch was not checkpointed",
                        true,
                        exception,
                        fromCheckpoint(options, MigrationStatus.FAILED, checkpoint, pageSize, -1L)
                );
            }

            if (page.complete()) {
                return verifyCompletedCheckpoint(options, checkpoint);
            }
            cursor = page.nextCursor();
        }
    }

    private MigrationCheckpoint loadCheckpoint(MigrationOptions options) {
        final Optional<MigrationCheckpoint> existing;
        try {
            existing = checkpointStore.load();
        } catch (RuntimeException exception) {
            throw failure(
                    "Unable to load the migration checkpoint",
                    true,
                    exception,
                    report(options, MigrationStatus.FAILED, 0L, 0L, 0L, 0L, 0L, -1L)
            );
        }
        MigrationCheckpoint checkpoint = existing.orElseGet(
                () -> MigrationCheckpoint.initial(options, clock.millis())
        );
        if (!checkpoint.migrationId().equals(options.migrationId())) {
            throw failure(
                    "Migration checkpoint belongs to a different migration",
                    false,
                    null,
                    report(options, MigrationStatus.FAILED, 0L, 0L, 0L, 0L, 0L, -1L)
            );
        }
        if (!checkpoint.assumedEmbedding().equals(options.assumedEmbedding())) {
            throw failure(
                    "Migration checkpoint embedding does not match configured assumedEmbedding",
                    false,
                    null,
                    fromCheckpoint(options, MigrationStatus.FAILED, checkpoint, 0L, -1L)
            );
        }
        return checkpoint;
    }

    private MigrationReport verifyCompletedCheckpoint(
            MigrationOptions options,
            MigrationCheckpoint checkpoint
    ) {
        final long targetRecords;
        try {
            targetRecords = targetCounter.count(options.assumedEmbedding());
            if (targetRecords < 0L) {
                throw new IllegalStateException("target count must not be negative");
            }
        } catch (RuntimeException exception) {
            throw failure(
                    "Unable to verify the Qdrant migration count",
                    true,
                    exception,
                    fromCheckpoint(options, MigrationStatus.FAILED, checkpoint, 0L, -1L)
            );
        }

        MigrationStatus status = targetRecords == checkpoint.successfulRecords()
                ? MigrationStatus.COMPLETED
                : MigrationStatus.COUNT_MISMATCH;
        MigrationReport report = fromCheckpoint(options, status, checkpoint, 0L, targetRecords);
        logReport(report);
        if (status == MigrationStatus.COUNT_MISMATCH) {
            throw failure("Qdrant migration count does not match the source", false, null, report);
        }
        return report;
    }

    private VectorRecordPage export(String cursor, MigrationOptions options) {
        VectorRecordPage page = exporter.exportPage(new VectorExportRequest(
                cursor,
                options.batchSize(),
                options.assumedEmbedding()
        ));
        if (page == null) {
            throw new IllegalStateException("exporter returned no page");
        }
        return page;
    }

    private static void validateEmbedding(List<VectorRecord> records, EmbeddingSpec assumedEmbedding) {
        for (VectorRecord record : records) {
            if (record == null || !record.embedding().equals(assumedEmbedding)) {
                throw new IllegalArgumentException("record embedding does not match assumedEmbedding");
            }
        }
    }

    private static void validateNextCursor(
            String currentCursor,
            VectorRecordPage page,
            Set<String> visitedCursors
    ) {
        if (page.complete()) {
            return;
        }
        String nextCursor = page.nextCursor();
        if (nextCursor.equals(currentCursor) || !visitedCursors.add(nextCursor)) {
            throw new IllegalStateException("export pagination cursor did not advance");
        }
    }

    private static MigrationReport fromCheckpoint(
            MigrationOptions options,
            MigrationStatus status,
            MigrationCheckpoint checkpoint,
            long failedRecords,
            long qdrantRecords
    ) {
        long source = safeAdd(checkpoint.sourceRecords(), failedRecords);
        return report(
                options,
                status,
                checkpoint.pagesCompleted(),
                source,
                checkpoint.successfulRecords(),
                failedRecords,
                0L,
                qdrantRecords
        );
    }

    private static MigrationReport report(
            MigrationOptions options,
            MigrationStatus status,
            long pages,
            long source,
            long successful,
            long failed,
            long planned,
            long qdrantRecords
    ) {
        return new MigrationReport(
                options.migrationId(),
                status,
                options.dryRun(),
                pages,
                source,
                successful,
                failed,
                planned,
                qdrantRecords,
                options.assumedEmbedding()
        );
    }

    private static VectorMigrationException failure(
            String safeMessage,
            boolean retryable,
            Throwable cause,
            MigrationReport report
    ) {
        return new VectorMigrationException(safeMessage, retryable, cause, report);
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("migration count overflow", exception);
        }
    }

    private static void logReport(MigrationReport report) {
        log.info(
                "Vector migration report migrationId={} status={} dryRun={} pages={} source={} success={} failed={} "
                        + "planned={} qdrant={} assumedModel={} assumedDimension={} assumedSchemaVersion={}",
                report.migrationId(),
                report.status(),
                report.dryRun(),
                report.pagesProcessed(),
                report.sourceRecords(),
                report.successfulRecords(),
                report.failedRecords(),
                report.plannedRecords(),
                report.qdrantRecords(),
                report.assumedEmbedding().model(),
                report.assumedEmbedding().dimension(),
                report.assumedEmbedding().schemaVersion()
        );
    }
}
