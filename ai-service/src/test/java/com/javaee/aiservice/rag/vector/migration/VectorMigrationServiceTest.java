package com.javaee.aiservice.rag.vector.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorExportRequest;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorRecordExporter;
import com.javaee.aiservice.rag.vector.contract.VectorRecordPage;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorMigrationServiceTest {

    private static final EmbeddingSpec EMBEDDING = new EmbeddingSpec("fixture-embedding", 2, 1);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDirectory;

    @Test
    void defaultsAreDryRunAndDryRunNeverWritesTargetOrCheckpoint() {
        FakeExporter exporter = twoPageExporter();
        MapQdrantBackend target = new MapQdrantBackend();
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        AtomicInteger countCalls = new AtomicInteger();
        VectorMigrationService service = new VectorMigrationService(
                exporter,
                target,
                checkpoints,
                embedding -> {
                    countCalls.incrementAndGet();
                    return target.size();
                },
                CLOCK
        );
        MigrationOptions options = MigrationOptions.defaults("fixture-migration", EMBEDDING);

        MigrationReport report = service.migrate(options);

        assertThat(options.dryRun()).isTrue();
        assertThat(options.batchSize()).isEqualTo(100);
        assertThat(report.status()).isEqualTo(MigrationStatus.DRY_RUN_COMPLETE);
        assertThat(report.sourceRecords()).isEqualTo(4L);
        assertThat(report.plannedRecords()).isEqualTo(4L);
        assertThat(report.successfulRecords()).isZero();
        assertThat(report.failedRecords()).isZero();
        assertThat(report.qdrantRecords()).isEqualTo(-1L);
        assertThat(report.countVerified()).isFalse();
        assertThat(exporter.requestedCursors).containsExactly("", "cursor-1");
        assertThat(target.batchCalls).hasValue(0);
        assertThat(target.size()).isZero();
        assertThat(checkpoints.loads).hasValue(0);
        assertThat(checkpoints.saves).hasValue(0);
        assertThat(countCalls).hasValue(0);
    }

    @Test
    void failedBatchKeepsLastCheckpointAndRetryIsIdempotent() {
        FakeExporter exporter = twoPageExporter();
        MapQdrantBackend target = new MapQdrantBackend();
        target.failPartiallyOnBatch = 2;
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        VectorMigrationService service = service(exporter, target, checkpoints, target::count);
        MigrationOptions options = liveOptions();

        assertThatThrownBy(() -> service.migrate(options))
                .isInstanceOfSatisfying(VectorMigrationException.class, exception -> {
                    MigrationReport report = exception.report();
                    assertThat(report.status()).isEqualTo(MigrationStatus.FAILED);
                    assertThat(report.sourceRecords()).isEqualTo(4L);
                    assertThat(report.successfulRecords()).isEqualTo(2L);
                    assertThat(report.failedRecords()).isEqualTo(2L);
                    assertThat(report.sourceRecords())
                            .isEqualTo(report.successfulRecords() + report.failedRecords());
                });

        MigrationCheckpoint afterFailure = checkpoints.checkpoint.orElseThrow();
        assertThat(afterFailure.cursor()).isEqualTo("cursor-1");
        assertThat(afterFailure.pagesCompleted()).isEqualTo(1L);
        assertThat(afterFailure.successfulRecords()).isEqualTo(2L);
        assertThat(target.size()).isEqualTo(3L);

        target.failPartiallyOnBatch = -1;
        MigrationReport recovered = service.migrate(options);

        assertThat(recovered.status()).isEqualTo(MigrationStatus.COMPLETED);
        assertThat(recovered.sourceRecords()).isEqualTo(4L);
        assertThat(recovered.successfulRecords()).isEqualTo(4L);
        assertThat(recovered.failedRecords()).isZero();
        assertThat(recovered.qdrantRecords()).isEqualTo(4L);
        assertThat(recovered.countsMatch()).isTrue();
        assertThat(exporter.requestedCursors).containsExactly("", "cursor-1", "cursor-1");
        assertThat(target.size()).isEqualTo(4L);

        int callsBeforeCompletedRerun = target.batchCalls.get();
        int exportCallsBeforeCompletedRerun = exporter.requestedCursors.size();
        MigrationReport rerun = service.migrate(options);
        assertThat(rerun.status()).isEqualTo(MigrationStatus.COMPLETED);
        assertThat(target.size()).isEqualTo(4L);
        assertThat(target.batchCalls).hasValue(callsBeforeCompletedRerun);
        assertThat(exporter.requestedCursors).hasSize(exportCallsBeforeCompletedRerun);
    }

    @Test
    void successfulCheckpointIsSavedOnlyAfterTargetBatchReturns() {
        FakeExporter exporter = new FakeExporter(Map.of(
                "", new VectorRecordPage(List.of(record("a"), record("b")), "", true)
        ));
        MapQdrantBackend target = new MapQdrantBackend();
        target.failPartiallyOnBatch = 1;
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        VectorMigrationService service = service(exporter, target, checkpoints, target::count);

        assertThatThrownBy(() -> service.migrate(liveOptions()))
                .isInstanceOfSatisfying(VectorMigrationException.class, exception -> {
                    assertThat(exception.report().successfulRecords()).isZero();
                    assertThat(exception.report().failedRecords()).isEqualTo(2L);
                });

        assertThat(checkpoints.checkpoint).isEmpty();
        assertThat(checkpoints.saves).hasValue(0);
        assertThat(target.size()).isEqualTo(1L);
    }

    @Test
    void incompatibleEmbeddingIsRejectedInsteadOfBeingGuessed() {
        EmbeddingSpec other = new EmbeddingSpec("other-model", 2, 1);
        VectorRecord incompatible = new VectorRecord(
                "incompatible",
                "document-incompatible",
                new float[]{1.0f, 0.0f},
                new VectorScope("user-a", "kb-a"),
                other,
                Map.of()
        );
        FakeExporter exporter = new FakeExporter(Map.of(
                "", new VectorRecordPage(List.of(incompatible), "", true)
        ));
        MapQdrantBackend target = new MapQdrantBackend();
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        VectorMigrationService service = service(exporter, target, checkpoints, target::count);

        assertThatThrownBy(() -> service.migrate(liveOptions()))
                .isInstanceOfSatisfying(VectorMigrationException.class, exception -> {
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception.report().sourceRecords()).isEqualTo(1L);
                    assertThat(exception.report().failedRecords()).isEqualTo(1L);
                    assertThat(exception.getMessage()).doesNotContain("other-model");
                });
        assertThat(target.batchCalls).hasValue(0);
        assertThat(checkpoints.checkpoint).isEmpty();
    }

    @Test
    void finalTargetCountMismatchIsAnExplicitFailure() {
        FakeExporter exporter = new FakeExporter(Map.of(
                "", new VectorRecordPage(List.of(record("a"), record("b")), "", true)
        ));
        MapQdrantBackend target = new MapQdrantBackend();
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        VectorMigrationService service = service(exporter, target, checkpoints, embedding -> 1L);

        assertThatThrownBy(() -> service.migrate(liveOptions()))
                .isInstanceOfSatisfying(VectorMigrationException.class, exception -> {
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception.report().status()).isEqualTo(MigrationStatus.COUNT_MISMATCH);
                    assertThat(exception.report().sourceRecords()).isEqualTo(2L);
                    assertThat(exception.report().successfulRecords()).isEqualTo(2L);
                    assertThat(exception.report().failedRecords()).isZero();
                    assertThat(exception.report().qdrantRecords()).isEqualTo(1L);
                    assertThat(exception.report().countsMatch()).isFalse();
                });
        assertThat(checkpoints.checkpoint.orElseThrow().complete()).isTrue();
    }

    @Test
    void fileCheckpointRoundTripsVersionedJsonAndReplacesAtomically() throws Exception {
        Path path = tempDirectory.resolve("nested").resolve("checkpoint.json");
        FileMigrationCheckpointStore store = new FileMigrationCheckpointStore(path, new ObjectMapper());
        MigrationOptions options = liveOptions();
        MigrationCheckpoint initial = MigrationCheckpoint.initial(options, CLOCK.millis());
        MigrationCheckpoint completed = initial.advance("", 2, true, CLOCK.millis() + 1L);

        store.save(initial);
        store.save(completed);

        assertThat(store.path()).isEqualTo(path.toAbsolutePath().normalize());
        assertThat(store.load()).contains(completed);
        assertThat(Files.exists(path.resolveSibling("checkpoint.json.tmp"))).isFalse();
        String json = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(json).contains("\"formatVersion\":1");
        assertThat(json).contains("\"migrationId\":\"fixture-migration\"");
        assertThat(json).doesNotContain("api-key");
        assertThat(json).doesNotContain("vector");
        assertThat(FileMigrationCheckpointStore.DEFAULT_PATH.toString())
                .isEqualTo(Path.of("var", "qdrant-migration", "checkpoint.json").toString());
    }

    @Test
    void checkpointEmbeddingAndMigrationIdentityMustMatchTheControlledRun() {
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        checkpoints.checkpoint = Optional.of(MigrationCheckpoint.initial(
                new MigrationOptions("another-migration", false, 100, EMBEDDING),
                CLOCK.millis()
        ));
        MapQdrantBackend target = new MapQdrantBackend();
        VectorMigrationService service = service(twoPageExporter(), target, checkpoints, target::count);

        assertThatThrownBy(() -> service.migrate(liveOptions()))
                .isInstanceOfSatisfying(VectorMigrationException.class, exception -> {
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception.getMessage()).contains("different migration");
                });
        assertThat(target.batchCalls).hasValue(0);
    }

    private static VectorMigrationService service(
            VectorRecordExporter exporter,
            MapQdrantBackend target,
            MigrationCheckpointStore checkpoints,
            MigrationTargetCounter counter
    ) {
        return new VectorMigrationService(exporter, target, checkpoints, counter, CLOCK);
    }

    private static MigrationOptions liveOptions() {
        return new MigrationOptions("fixture-migration", false, 2, EMBEDDING);
    }

    private static FakeExporter twoPageExporter() {
        Map<String, VectorRecordPage> pages = new LinkedHashMap<>();
        pages.put("", new VectorRecordPage(List.of(record("a"), record("b")), "cursor-1", false));
        pages.put("cursor-1", new VectorRecordPage(List.of(record("c"), record("d")), "", true));
        return new FakeExporter(pages);
    }

    private static VectorRecord record(String id) {
        return new VectorRecord(
                id,
                "document-" + id,
                new float[]{1.0f, 0.0f},
                new VectorScope("user-a", "kb-a"),
                EMBEDDING,
                Map.of("fixture", true)
        );
    }

    private static final class FakeExporter implements VectorRecordExporter {

        private final Map<String, VectorRecordPage> pages;
        private final List<String> requestedCursors = new ArrayList<>();

        private FakeExporter(Map<String, VectorRecordPage> pages) {
            this.pages = pages;
        }

        @Override
        public VectorRecordPage exportPage(VectorExportRequest request) {
            requestedCursors.add(request.cursor());
            assertThat(request.assumedEmbedding()).isEqualTo(EMBEDDING);
            VectorRecordPage page = pages.get(request.cursor());
            if (page == null) {
                throw new IllegalStateException("unexpected fixture cursor");
            }
            return page;
        }
    }

    private static final class InMemoryCheckpointStore implements MigrationCheckpointStore {

        private Optional<MigrationCheckpoint> checkpoint = Optional.empty();
        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger saves = new AtomicInteger();

        @Override
        public Optional<MigrationCheckpoint> load() {
            loads.incrementAndGet();
            return checkpoint;
        }

        @Override
        public void save(MigrationCheckpoint checkpoint) {
            saves.incrementAndGet();
            this.checkpoint = Optional.of(checkpoint);
        }
    }

    private static final class MapQdrantBackend implements VectorBackend {

        private final Map<String, VectorRecord> records = new LinkedHashMap<>();
        private final AtomicInteger batchCalls = new AtomicInteger();
        private volatile int failPartiallyOnBatch = -1;

        @Override
        public VectorBackendType type() {
            return VectorBackendType.QDRANT;
        }

        @Override
        public void upsert(VectorRecord record) {
            records.put(key(record), record);
        }

        @Override
        public void upsertBatch(List<VectorRecord> batch) {
            int call = batchCalls.incrementAndGet();
            if (call == failPartiallyOnBatch) {
                if (!batch.isEmpty()) {
                    upsert(batch.get(0));
                }
                throw new IllegalStateException("fixture batch interruption");
            }
            batch.forEach(this::upsert);
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            return List.of();
        }

        @Override
        public void deleteById(String id, VectorScope scope) {
            records.remove(scope.userId() + "\n" + scope.knowledgeBaseId() + "\n" + id);
        }

        @Override
        public long deleteByDocument(String documentId, VectorScope scope) {
            int before = records.size();
            records.entrySet().removeIf(entry -> entry.getValue().scope().equals(scope)
                    && entry.getValue().documentId().equals(documentId));
            return before - records.size();
        }

        @Override
        public VectorBackendHealth health() {
            return new VectorBackendHealth(VectorBackendType.QDRANT, true, "fixture", Map.of());
        }

        long count(EmbeddingSpec embedding) {
            return records.values().stream().filter(record -> record.embedding().equals(embedding)).count();
        }

        long size() {
            return records.size();
        }

        private static String key(VectorRecord record) {
            return record.scope().userId() + "\n" + record.scope().knowledgeBaseId() + "\n" + record.id();
        }
    }
}
