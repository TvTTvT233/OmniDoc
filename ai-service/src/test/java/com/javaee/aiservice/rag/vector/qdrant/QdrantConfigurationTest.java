package com.javaee.aiservice.rag.vector.qdrant;

import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QdrantConfiguration.class);

    @Test
    void baselineModeDoesNotCreateAnyQdrantClientOrBackend() {
        contextRunner
                .withPropertyValues("ai.vector.mode=baseline")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(QdrantClient.class);
                    assertThat(context).doesNotHaveBean(QdrantVectorBackend.class);
                    assertThat(context).doesNotHaveBean(QdrantProperties.class);
                });
    }

    @Test
    void missingModeDefaultsToBaselineAndDoesNotCreateClient() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(QdrantClient.class));
    }
}
