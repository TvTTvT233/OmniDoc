package com.javaee.aiservice.rag.vector.acceptance;

import com.javaee.aiservice.rag.vector.acceptance.Qv04IntegratedAcceptanceProvider.MigrationObservation;
import com.javaee.aiservice.rag.vector.acceptance.Qv04IntegratedAcceptanceProvider.ModeObservation;
import com.javaee.aiservice.rag.vector.acceptance.Qv04IntegratedAcceptanceProvider.ShadowObservation;
import com.javaee.aiservice.rag.vector.acceptance.VectorBackendAcceptanceHarness.BackendReport;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs only through scripts/qdrant/run-qv04-acceptance.ps1 -Integrated.
 */
class Qv04IntegratedGateTest {

    @Test
    void integratedProviderExecutesEveryFrozenHardGate() throws Exception {
        String collection = System.getProperty("qv04.test.collection");
        Qv04CollectionBoundary.requireTestName(collection);
        Qv04Fixture fixture = Qv04Fixture.load();

        try (Qv04IntegratedAcceptanceProvider provider = createProvider()) {
            BackendReport baseline = VectorBackendAcceptanceHarness.exerciseBackend(
                    "integrated-local-redis-hnsw",
                    VectorBackendType.LOCAL_REDIS_HNSW,
                    provider.localBackend(),
                    fixture,
                    true,
                    "INTEGRATED_BACKEND");
            BackendReport qdrant = VectorBackendAcceptanceHarness.exerciseBackend(
                    "integrated-qdrant",
                    VectorBackendType.QDRANT,
                    provider.qdrantBackend(),
                    fixture,
                    true,
                    "INTEGRATED_BACKEND");

            assertThat(qdrant.recallAt5()).isGreaterThanOrEqualTo(baseline.recallAt5());
            assertThat(baseline.tenantLeakage()).isZero();
            assertThat(qdrant.tenantLeakage()).isZero();
            assertThat(baseline.deleteResidue()).isZero();
            assertThat(qdrant.deleteResidue()).isZero();

            VectorBackendAcceptanceHarness.LifecycleReport lifecycle =
                    VectorBackendAcceptanceHarness.exerciseLifecycle(provider.qdrantLifecycle(), fixture);
            assertThat(lifecycle.secondClientVisible()).isTrue();
            assertThat(lifecycle.restartPersistent()).isTrue();

            assertModeBoundaries(provider);
            assertShadow(provider, fixture);
            assertMigration(provider, fixture);
            writeReportsIfRequested(baseline, qdrant, lifecycle);
        }
    }

    private static Qv04IntegratedAcceptanceProvider createProvider() throws Exception {
        String className = System.getProperty("qv04.acceptance.providerClass", "").trim();
        assertThat(className)
                .as("Integrated QV04 requires -Dqv04.acceptance.providerClass supplied by the integration task")
                .isNotBlank();
        Class<?> providerClass = Class.forName(className);
        assertThat(providerClass).isAssignableTo(Qv04IntegratedAcceptanceProvider.class);
        return (Qv04IntegratedAcceptanceProvider) providerClass.getDeclaredConstructor().newInstance();
    }

    private static void assertModeBoundaries(Qv04IntegratedAcceptanceProvider provider) {
        ModeObservation baseline = provider.observeMode(VectorStoreMode.BASELINE);
        assertThat(baseline.mode()).isEqualTo(VectorStoreMode.BASELINE);
        assertThat(baseline.resultBackend()).isEqualTo(VectorBackendType.LOCAL_REDIS_HNSW);
        assertThat(baseline.qdrantClientCreations()).isZero();

        ModeObservation qdrant = provider.observeMode(VectorStoreMode.QDRANT);
        assertThat(qdrant.mode()).isEqualTo(VectorStoreMode.QDRANT);
        assertThat(qdrant.resultBackend()).isEqualTo(VectorBackendType.QDRANT);
        assertThat(qdrant.redisIndexScans()).isZero();
        writeObservationIfRequested("baseline-mode.json", baseline);
        writeObservationIfRequested("qdrant-mode.json", qdrant);
    }

    private static void assertShadow(Qv04IntegratedAcceptanceProvider provider, Qv04Fixture fixture) {
        Qv04Fixture.QueryCase query = fixture.queries().stream()
                .filter(item -> item.scopeRef().equals("user-c-rules"))
                .filter(item -> !item.noAnswer())
                .findFirst()
                .orElseThrow();

        ShadowObservation successful = provider.runShadow(query.query(), false);
        assertThat(successful.returnedIds()).containsExactlyElementsOf(successful.baselineIds());
        assertThat(successful.overlapAtK()).isBetween(0.0d, 1.0d);
        assertThat(successful.sampleCount()).isPositive();
        assertThat(successful.qdrantError()).isNull();
        assertThat(successful.loggedVectorTextOrCredential()).isFalse();

        ShadowObservation failedQdrant = provider.runShadow(query.query(), true);
        assertThat(failedQdrant.returnedIds()).containsExactlyElementsOf(failedQdrant.baselineIds());
        assertThat(failedQdrant.qdrantError()).isNotNull();
        assertThat(failedQdrant.loggedVectorTextOrCredential()).isFalse();
        writeObservationIfRequested("shadow-success.json", successful);
        writeObservationIfRequested("shadow-failure.json", failedQdrant);
    }

    private static void assertMigration(Qv04IntegratedAcceptanceProvider provider, Qv04Fixture fixture) {
        MigrationObservation migration = provider.runMigration(fixture);
        assertThat(migration.dryRunTargetWrites()).isZero();
        assertThat(migration.sourceCount()).isEqualTo(fixture.records().size());
        assertThat(migration.sourceCount()).isEqualTo(migration.successCount() + migration.failureCount());
        assertThat(migration.failureCount()).isZero();
        assertThat(migration.targetCount()).isEqualTo(migration.sourceCount());
        assertThat(migration.targetCountAfterRerun()).isEqualTo(migration.targetCount());
        assertThat(migration.checkpointAfterFailedBatch()).isEqualTo(migration.checkpointBeforeFailedBatch());
        assertThat(migration.checkpointAfterResume()).isNotEqualTo(migration.checkpointAfterFailedBatch());
        assertThat(migration.assumedEmbeddingReported()).isTrue();
        assertThat(migration.sourceDeleted()).isFalse();
        writeObservationIfRequested("migration.json", migration);
    }

    private static void writeReportsIfRequested(
            BackendReport baseline,
            BackendReport qdrant,
            VectorBackendAcceptanceHarness.LifecycleReport lifecycle) {
        String reportDirectory = System.getProperty("qv04.report.dir", "").trim();
        if (reportDirectory.isEmpty()) {
            return;
        }
        Path root = Path.of(reportDirectory);
        VectorBackendAcceptanceHarness.writeReport(root.resolve("baseline-metrics.json"), baseline);
        VectorBackendAcceptanceHarness.writeReport(root.resolve("qdrant-metrics.json"), qdrant);
        VectorBackendAcceptanceHarness.writeReport(root.resolve("qdrant-lifecycle.json"), lifecycle);
    }

    private static void writeObservationIfRequested(String fileName, Object observation) {
        String reportDirectory = System.getProperty("qv04.report.dir", "").trim();
        if (!reportDirectory.isEmpty()) {
            VectorBackendAcceptanceHarness.writeReport(Path.of(reportDirectory).resolve(fileName), observation);
        }
    }
}
