package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Spring lifecycle for the official Qdrant client and backend. */
@Configuration(proxyBeanMethods = false)
@Conditional(QdrantEnabledCondition.class)
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfiguration {

    @Bean(destroyMethod = "close")
    QdrantClient qdrantClient(QdrantProperties properties) {
        properties.validate();
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                        properties.getHost(),
                        properties.getGrpcPort(),
                        false,
                        false)
                .withTimeout(properties.requestTimeout());
        if (!properties.getApiKey().isBlank()) {
            builder.withApiKey(properties.getApiKey());
        }
        return new QdrantClient(builder.build());
    }

    @Bean
    QdrantOperations qdrantOperations(QdrantClient client) {
        return new OfficialQdrantOperations(client);
    }

    @Bean
    UuidV5VectorPointIdFactory qdrantVectorPointIdFactory() {
        return new UuidV5VectorPointIdFactory();
    }

    @Bean
    QdrantCollectionManager qdrantCollectionManager(
            QdrantOperations operations,
            QdrantProperties properties,
            @Value("${ai.vector.embedding.model}") String embeddingModel,
            @Value("${ai.vector.dimension}") int embeddingDimension,
            @Value("${ai.vector.embedding.schema-version}") int schemaVersion
    ) {
        EmbeddingSpec embedding = new EmbeddingSpec(
                embeddingModel,
                embeddingDimension,
                schemaVersion);
        return new QdrantCollectionManager(
                operations,
                properties,
                embedding,
                new QdrantCollectionNameFactory());
    }

    @Bean("qdrantVectorBackend")
    QdrantVectorBackend qdrantVectorBackend(
            QdrantOperations operations,
            QdrantProperties properties,
            QdrantCollectionManager collectionManager,
            UuidV5VectorPointIdFactory pointIdFactory
    ) {
        return new QdrantVectorBackend(
                operations,
                properties,
                collectionManager,
                pointIdFactory);
    }
}
