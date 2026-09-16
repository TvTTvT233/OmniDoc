package com.javaee.aiservice.rag.vector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VectorConfigurationFileTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void migrationSettingsStayAtTheDocumentedVectorLevel() throws IOException {
        assertMigrationProperties("application.yml");
        assertMigrationProperties("application-local.yml");
    }

    private void assertMigrationProperties(String resource) throws IOException {
        List<PropertySource<?>> sources = loader.load(resource, new ClassPathResource(resource));
        assertThat(sources)
                .anySatisfy(source -> {
                    assertThat(source.getProperty("ai.vector.migration.batch-size")).isEqualTo(100);
                    assertThat(source.getProperty("ai.vector.migration.checkpoint-path"))
                            .isEqualTo("${VECTOR_MIGRATION_CHECKPOINT_PATH:var/qdrant-migration/checkpoint.json}");
                    assertThat(source.getProperty("ai.vector.shadow.migration.batch-size")).isNull();
                });
    }
}
