package com.javaee.aiservice.rag.vector.qdrant;

import com.google.common.util.concurrent.ListenableFuture;
import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.AliasDescription;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class QdrantIntegrationSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private QdrantIntegrationSupport() {
    }

    static Runtime open(String alias, String collectionPrefix, EmbeddingSpec embedding) {
        QdrantProperties properties = properties(alias);
        QdrantClient client = new QdrantConfiguration().qdrantClient(properties);
        try {
            OfficialQdrantOperations operations = new OfficialQdrantOperations(client);
            QdrantCollectionManager manager = new QdrantCollectionManager(
                    operations,
                    properties,
                    embedding,
                    new QdrantCollectionNameFactory(collectionPrefix));
            QdrantVectorBackend backend = new QdrantVectorBackend(
                    operations,
                    properties,
                    manager,
                    new UuidV5VectorPointIdFactory());
            return new Runtime(client, operations, manager, backend, properties);
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    static QdrantClient client(String alias) {
        return new QdrantConfiguration().qdrantClient(properties(alias));
    }

    static void cleanup(QdrantClient client, String alias, String collection) {
        if (!testResource(alias) || !testResource(collection)) {
            throw new IllegalArgumentException(
                    "Integration cleanup is restricted to test_ and qv04_test_ resources");
        }
        List<AliasDescription> aliases = await(client.listAliasesAsync(TIMEOUT));
        if (aliases.stream().anyMatch(candidate -> alias.equals(candidate.getAliasName()))) {
            await(client.deleteAliasAsync(alias, TIMEOUT));
        }
        if (await(client.collectionExistsAsync(collection, TIMEOUT))) {
            await(client.deleteCollectionAsync(collection, TIMEOUT));
        }
    }

    private static boolean testResource(String name) {
        return name != null && (name.startsWith("test_") || name.startsWith("qv04_test_"));
    }

    private static QdrantProperties properties(String alias) {
        QdrantProperties properties = new QdrantProperties();
        properties.setHost(System.getProperty("qdrant.host", "localhost"));
        properties.setGrpcPort(Integer.getInteger("qdrant.grpc-port", 6334));
        properties.setApiKey(System.getProperty("qdrant.api-key", ""));
        properties.setCollectionAlias(alias);
        properties.setConnectTimeoutMs(TIMEOUT.toMillis());
        properties.setRequestTimeoutMs(TIMEOUT.toMillis());
        return properties;
    }

    private static <T> T await(ListenableFuture<T> future) {
        try {
            return future.get(TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Qdrant integration cleanup failed", exception);
        }
    }

    record Runtime(
            QdrantClient client,
            OfficialQdrantOperations operations,
            QdrantCollectionManager manager,
            QdrantVectorBackend backend,
            QdrantProperties properties
    ) implements AutoCloseable {

        @Override
        public void close() {
            client.close();
        }
    }
}
