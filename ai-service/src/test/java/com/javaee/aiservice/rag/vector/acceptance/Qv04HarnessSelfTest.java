package com.javaee.aiservice.rag.vector.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.rag.vector.acceptance.VectorBackendAcceptanceHarness.BackendReport;
import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Qv04HarnessSelfTest {

    private final Qv04Fixture fixture = Qv04Fixture.load();

    @TempDir
    Path temporaryDirectory;

    @TestFactory
    Stream<DynamicTest> sameHarnessRunsAgainstTwoBackendTypes() {
        return Stream.of(VectorBackendType.LOCAL_REDIS_HNSW, VectorBackendType.QDRANT)
                .map(type -> DynamicTest.dynamicTest(type.configValue(), () -> {
                    InMemoryBackend backend = new InMemoryBackend(type, new LinkedHashMap<>());
                    BackendReport report = VectorBackendAcceptanceHarness.exerciseBackend(
                            "harness-" + type.configValue(),
                            type,
                            backend,
                            fixture,
                            false,
                            "HARNESS_SELF_TEST_ONLY");

                    assertThat(report.realImplementation()).isFalse();
                    assertThat(report.recordCount()).isEqualTo(60);
                    assertThat(report.recallAt5()).isEqualTo(1.0d);
                    assertThat(report.mrr()).isEqualTo(1.0d);
                    assertThat(report.tenantLeakage()).isZero();
                    assertThat(report.deleteResidue()).isZero();
                }));
    }

    @Test
    void reportWriterKeepsSelfTestEvidenceClearlyLabeled() throws Exception {
        BackendReport report = VectorBackendAcceptanceHarness.exerciseBackend(
                "harness-local",
                VectorBackendType.LOCAL_REDIS_HNSW,
                new InMemoryBackend(VectorBackendType.LOCAL_REDIS_HNSW, new LinkedHashMap<>()),
                fixture,
                false,
                "HARNESS_SELF_TEST_ONLY");
        Path output = temporaryDirectory.resolve("backend-report.json");

        VectorBackendAcceptanceHarness.writeReport(output, report);

        JsonNode json = new ObjectMapper().readTree(output.toFile());
        assertThat(json.path("realImplementation").asBoolean()).isFalse();
        assertThat(json.path("evidenceLevel").asText()).isEqualTo("HARNESS_SELF_TEST_ONLY");
        assertThat(json.path("p50Micros").isNumber()).isTrue();
        assertThat(json.path("p95Micros").isNumber()).isTrue();
        assertThat(json.path("upsertRecordsPerSecond").asDouble()).isPositive();
    }

    @Test
    void harnessRejectsCrossTenantLeakage() {
        VectorBackend leaking = new LeakingBackend(VectorBackendType.QDRANT, new LinkedHashMap<>(), fixture);

        assertThatThrownBy(() -> VectorBackendAcceptanceHarness.exerciseBackend(
                "leaking-backend", VectorBackendType.QDRANT, leaking, fixture, false, "NEGATIVE_SELF_TEST"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("scope inventory differs");
    }

    @Test
    void harnessRejectsDeleteResidue() {
        VectorBackend residue = new DeleteResidueBackend(VectorBackendType.QDRANT, new LinkedHashMap<>());

        assertThatThrownBy(() -> VectorBackendAcceptanceHarness.exerciseBackend(
                "delete-residue-backend", VectorBackendType.QDRANT, residue, fixture, false, "NEGATIVE_SELF_TEST"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("delete residue");
    }

    @Test
    void lifecycleGateUsesTwoClientsAndAReplacementClient() {
        Map<String, VectorRecord> shared = new LinkedHashMap<>();
        InMemoryBackend first = new InMemoryBackend(VectorBackendType.QDRANT, shared);
        InMemoryBackend second = new InMemoryBackend(VectorBackendType.QDRANT, shared);

        VectorBackendAcceptanceHarness.LifecycleReport report = VectorBackendAcceptanceHarness.exerciseLifecycle(
                new VectorBackendAcceptanceHarness.VectorBackendLifecycle() {
                    @Override
                    public VectorBackend firstClient() {
                        return first;
                    }

                    @Override
                    public VectorBackend secondClient() {
                        return second;
                    }

                    @Override
                    public VectorBackend restartAndGetClient() {
                        return new InMemoryBackend(VectorBackendType.QDRANT, shared);
                    }
                },
                fixture);

        assertThat(report.secondClientVisible()).isTrue();
        assertThat(report.restartPersistent()).isTrue();
    }

    private static class InMemoryBackend implements VectorBackend {
        private final VectorBackendType type;
        protected final Map<String, VectorRecord> records;

        private InMemoryBackend(VectorBackendType type, Map<String, VectorRecord> records) {
            this.type = type;
            this.records = records;
        }

        @Override
        public VectorBackendType type() {
            return type;
        }

        @Override
        public void upsert(VectorRecord record) {
            records.put(record.id(), record);
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            VectorFilter filter = query.filter();
            return records.values().stream()
                    .filter(record -> record.scope().equals(filter.scope()))
                    .filter(record -> filter.documentId() == null || record.documentId().equals(filter.documentId()))
                    .map(record -> new VectorMatch(
                            record.id(),
                            record.documentId(),
                            (float) Qv04Fixture.cosine(query.vector(), record.vector()),
                            record.metadata()))
                    .sorted(Comparator.comparing(VectorMatch::score).reversed().thenComparing(VectorMatch::id))
                    .limit(query.topK())
                    .toList();
        }

        @Override
        public void deleteById(String id, VectorScope scope) {
            VectorRecord record = records.get(id);
            if (record != null && record.scope().equals(scope)) {
                records.remove(id);
            }
        }

        @Override
        public long deleteByDocument(String documentId, VectorScope scope) {
            List<String> ids = records.values().stream()
                    .filter(record -> record.scope().equals(scope))
                    .filter(record -> record.documentId().equals(documentId))
                    .map(VectorRecord::id)
                    .toList();
            ids.forEach(records::remove);
            return ids.size();
        }

        @Override
        public VectorBackendHealth health() {
            return new VectorBackendHealth(type, true, "synthetic in-memory harness", Map.of("synthetic", true));
        }
    }

    private static final class LeakingBackend extends InMemoryBackend {
        private final Qv04Fixture fixture;

        private LeakingBackend(VectorBackendType type, Map<String, VectorRecord> records, Qv04Fixture fixture) {
            super(type, records);
            this.fixture = fixture;
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            List<VectorMatch> matches = new ArrayList<>(super.search(query));
            fixture.records().stream()
                    .map(Qv04Fixture.ExpandedRecord::record)
                    .filter(record -> !record.scope().equals(query.filter().scope()))
                    .findFirst()
                    .ifPresent(record -> matches.add(new VectorMatch(
                            record.id(), record.documentId(), 1.0f, record.metadata())));
            return matches;
        }
    }

    private static final class DeleteResidueBackend extends InMemoryBackend {

        private DeleteResidueBackend(VectorBackendType type, Map<String, VectorRecord> records) {
            super(type, records);
        }

        @Override
        public void deleteById(String id, VectorScope scope) {
            // Deliberately retains the record to prove that the harness detects residue.
        }

        @Override
        public long deleteByDocument(String documentId, VectorScope scope) {
            return records.values().stream()
                    .filter(record -> record.scope().equals(scope))
                    .filter(record -> record.documentId().equals(documentId))
                    .count();
        }
    }
}
