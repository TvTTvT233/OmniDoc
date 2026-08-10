package com.javaee.aiservice.rag.vector.routing;

import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendRouter;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;
import com.javaee.aiservice.rag.vector.local.LocalRedisHnswVectorBackend;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Production Spring wiring for shadow and Qdrant-only vector routing. */
@Configuration(proxyBeanMethods = false)
@Conditional(VectorRoutingEnabledCondition.class)
public class VectorRoutingConfiguration {

    @Bean
    ShadowMetricsRecorder vectorShadowMetricsRecorder() {
        return new InMemoryShadowMetricsRecorder();
    }

    @Bean(destroyMethod = "close")
    VectorBackendRouter vectorBackendRouter(
            @Value("${ai.vector.mode}") String configuredMode,
            @Value("${ai.vector.shadow.sample-rate:1.0}") double sampleRate,
            @Value("${ai.vector.shadow.timeout-ms:3000}") long timeoutMillis,
            ObjectProvider<LocalRedisHnswVectorBackend> localProvider,
            @Qualifier("qdrantVectorBackend") VectorBackend qdrant,
            ShadowMetricsRecorder metrics
    ) {
        VectorStoreMode mode = VectorStoreMode.fromConfig(configuredMode.trim());
        VectorBackend baseline = mode == VectorStoreMode.QDRANT
                ? new DeferredLocalBackend(localProvider)
                : localProvider.getObject();
        return new DefaultVectorBackendRouter(
                mode,
                baseline,
                () -> qdrant,
                new ShadowRoutingOptions(sampleRate, timeoutMillis),
                metrics);
    }

    /**
     * The QV03 router validates the baseline type at construction. This adapter preserves that
     * invariant without creating or scanning the local backend in Qdrant-only mode.
     */
    private static final class DeferredLocalBackend implements VectorBackend {

        private final ObjectProvider<LocalRedisHnswVectorBackend> provider;

        private DeferredLocalBackend(ObjectProvider<LocalRedisHnswVectorBackend> provider) {
            this.provider = provider;
        }

        @Override
        public VectorBackendType type() {
            return VectorBackendType.LOCAL_REDIS_HNSW;
        }

        @Override
        public void upsert(VectorRecord record) {
            delegate().upsert(record);
        }

        @Override
        public void upsertBatch(List<VectorRecord> records) {
            delegate().upsertBatch(records);
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            return delegate().search(query);
        }

        @Override
        public void deleteById(String id, VectorScope scope) {
            delegate().deleteById(id, scope);
        }

        @Override
        public long deleteByDocument(String documentId, VectorScope scope) {
            return delegate().deleteByDocument(documentId, scope);
        }

        @Override
        public VectorBackendHealth health() {
            return delegate().health();
        }

        private LocalRedisHnswVectorBackend delegate() {
            LocalRedisHnswVectorBackend backend = provider.getIfAvailable();
            if (backend == null) {
                throw new IllegalStateException("Local vector backend is disabled in qdrant mode");
            }
            return backend;
        }
    }
}
