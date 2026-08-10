package com.javaee.aiservice.rag.vector.routing;

import com.javaee.aiservice.rag.vector.contract.VectorBackendRouter;
import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;
import com.javaee.aiservice.rag.vector.local.LocalRedisHnswVectorBackend;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

class VectorRoutingConfigurationTest {

    @Test
    void qdrantModeCreatesRouterWithoutAConfiguredLocalBackend() {
        VectorBackend qdrant = new TypeOnlyBackend(VectorBackendType.QDRANT);

        new ApplicationContextRunner()
                .withUserConfiguration(VectorRoutingConfiguration.class)
                .withBean("qdrantVectorBackend", VectorBackend.class, () -> qdrant)
                .withPropertyValues("ai.vector.mode=qdrant")
                .run(context -> {
                    assertThat(context).hasSingleBean(VectorBackendRouter.class);
                    assertThat(context).doesNotHaveBean(LocalRedisHnswVectorBackend.class);
                    VectorBackendRouter router = context.getBean(VectorBackendRouter.class);
                    assertThat(router.mode()).isEqualTo(VectorStoreMode.QDRANT);
                    assertThat(router.type()).isEqualTo(VectorBackendType.QDRANT);
                });
    }

    @Test
    void shadowModeRequiresAndUsesTheLocalBackendAsPrimary() {
        LocalRedisHnswVectorBackend local = mock(LocalRedisHnswVectorBackend.class);
        VectorBackend qdrant = new TypeOnlyBackend(VectorBackendType.QDRANT);
        when(local.type()).thenReturn(VectorBackendType.LOCAL_REDIS_HNSW);

        new ApplicationContextRunner()
                .withUserConfiguration(VectorRoutingConfiguration.class)
                .withBean(LocalRedisHnswVectorBackend.class, () -> local)
                .withBean("qdrantVectorBackend", VectorBackend.class, () -> qdrant)
                .withPropertyValues("ai.vector.mode=shadow")
                .run(context -> {
                    VectorBackendRouter router = context.getBean(VectorBackendRouter.class);
                    assertThat(router.mode()).isEqualTo(VectorStoreMode.SHADOW);
                    assertThat(router.type()).isEqualTo(VectorBackendType.LOCAL_REDIS_HNSW);
                });
    }

    @Test
    void baselineModeDoesNotActivateTheMultiBackendRouter() {
        new ApplicationContextRunner()
                .withUserConfiguration(VectorRoutingConfiguration.class)
                .withPropertyValues("ai.vector.mode=baseline")
                .run(context -> assertThat(context).doesNotHaveBean(VectorBackendRouter.class));
    }

    private record TypeOnlyBackend(VectorBackendType type) implements VectorBackend {

        @Override
        public void upsert(VectorRecord record) {
            throw unsupported();
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            throw unsupported();
        }

        @Override
        public void deleteById(String id, VectorScope scope) {
            throw unsupported();
        }

        @Override
        public long deleteByDocument(String documentId, VectorScope scope) {
            throw unsupported();
        }

        @Override
        public VectorBackendHealth health() {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("type-only fixture");
        }
    }
}
