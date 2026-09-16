package com.javaee.aiservice.rag.vector.migration;

import com.javaee.aiservice.rag.vector.local.LocalRedisHnswVectorBackend;
import com.javaee.aiservice.rag.vector.qdrant.QdrantVectorBackend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Internal migration beans; no public controller or automatic migration is exposed. */
@Configuration(proxyBeanMethods = false)
@Conditional(ShadowMigrationEnabledCondition.class)
public class VectorMigrationConfiguration {

    @Bean
    MigrationCheckpointStore vectorMigrationCheckpointStore(
            @Value("${ai.vector.migration.checkpoint-path:var/qdrant-migration/checkpoint.json}")
            String checkpointPath
    ) {
        return new FileMigrationCheckpointStore(Path.of(checkpointPath));
    }

    @Bean
    VectorMigrationService vectorMigrationService(
            LocalRedisHnswVectorBackend local,
            QdrantVectorBackend qdrant,
            MigrationCheckpointStore checkpointStore
    ) {
        return new VectorMigrationService(local, qdrant, checkpointStore, qdrant);
    }
}
