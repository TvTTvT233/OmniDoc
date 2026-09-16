package com.javaee.aiservice.rag.vector.acceptance;

import com.javaee.aiservice.rag.vector.acceptance.VectorBackendAcceptanceHarness.VectorBackendLifecycle;
import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;

import java.util.List;

/**
 * Test-only bridge supplied by the integration task after QV01-QV03 are merged.
 * QV04 intentionally does not depend on implementation constructors or Spring wiring.
 */
public interface Qv04IntegratedAcceptanceProvider extends AutoCloseable {

    VectorBackend localBackend();

    VectorBackend qdrantBackend();

    VectorBackendLifecycle qdrantLifecycle();

    ModeObservation observeMode(VectorStoreMode mode);

    ShadowObservation runShadow(VectorQuery query, boolean injectQdrantFailure);

    MigrationObservation runMigration(Qv04Fixture fixture);

    @Override
    default void close() {
    }

    record ModeObservation(
            VectorStoreMode mode,
            VectorBackendType resultBackend,
            long qdrantClientCreations,
            long redisIndexScans) {
    }

    record ShadowObservation(
            List<String> baselineIds,
            List<String> returnedIds,
            List<String> qdrantIds,
            double overlapAtK,
            long baselineLatencyMicros,
            long qdrantLatencyMicros,
            VectorErrorCode qdrantError,
            long sampleCount,
            boolean loggedVectorTextOrCredential) {
    }

    record MigrationObservation(
            long dryRunTargetWrites,
            long sourceCount,
            long successCount,
            long failureCount,
            long targetCount,
            String checkpointBeforeFailedBatch,
            String checkpointAfterFailedBatch,
            String checkpointAfterResume,
            long targetCountAfterRerun,
            boolean assumedEmbeddingReported,
            boolean sourceDeleted) {
    }
}
