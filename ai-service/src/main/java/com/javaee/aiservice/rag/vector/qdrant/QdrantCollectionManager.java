package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the configured vector-space collection once and validates the stable alias before use.
 * Existing aliases are never reassigned automatically.
 */
public final class QdrantCollectionManager {

    private static final Map<String, PayloadSchemaType> REQUIRED_PAYLOAD_INDEXES;

    static {
        Map<String, PayloadSchemaType> indexes = new LinkedHashMap<>();
        indexes.put(QdrantPayloadMapper.USER_ID, PayloadSchemaType.Keyword);
        indexes.put(QdrantPayloadMapper.KNOWLEDGE_BASE_ID, PayloadSchemaType.Keyword);
        indexes.put(QdrantPayloadMapper.DOCUMENT_ID, PayloadSchemaType.Keyword);
        indexes.put(QdrantPayloadMapper.EMBEDDING_MODEL, PayloadSchemaType.Keyword);
        indexes.put(QdrantPayloadMapper.SCHEMA_VERSION, PayloadSchemaType.Integer);
        REQUIRED_PAYLOAD_INDEXES = Map.copyOf(indexes);
    }

    private final QdrantOperations operations;
    private final QdrantProperties properties;
    private final EmbeddingSpec embedding;
    private final String collection;
    private volatile String serverVersion = "";

    QdrantCollectionManager(
            QdrantOperations operations,
            QdrantProperties properties,
            EmbeddingSpec embedding,
            QdrantCollectionNameFactory collectionNameFactory
    ) {
        if (operations == null || properties == null || embedding == null
                || collectionNameFactory == null) {
            throw new IllegalArgumentException("Qdrant collection manager dependencies must not be null");
        }
        properties.validate();
        this.operations = operations;
        this.properties = properties;
        this.embedding = embedding;
        this.collection = collectionNameFactory.create(embedding);
        initialize();
    }

    public String collection() {
        return collection;
    }

    public String alias() {
        return properties.getCollectionAlias();
    }

    public EmbeddingSpec embedding() {
        return embedding;
    }

    public String serverVersion() {
        return serverVersion;
    }

    public void validateEmbedding(EmbeddingSpec candidate) {
        if (candidate == null) {
            throw configurationError("Embedding specification must not be null");
        }
        if (candidate.dimension() != embedding.dimension()) {
            throw new VectorBackendException(
                    VectorBackendType.QDRANT,
                    VectorErrorCode.VECTOR_DIMENSION_MISMATCH,
                    "Vector dimension does not match the active Qdrant collection",
                    false);
        }
        if (!candidate.model().equals(embedding.model())
                || candidate.schemaVersion() != embedding.schemaVersion()) {
            throw configurationError("Embedding model or schema does not match the active Qdrant collection");
        }
    }

    /** Performs a non-mutating health and compatibility check. */
    public String validateReady() {
        try {
            String version = operations.health(properties.connectTimeout());
            QdrantCollectionDescriptor descriptor = operations.getCollection(
                    collection,
                    properties.requestTimeout());
            validateCollection(collection, descriptor);
            validateRequiredIndexes(descriptor);
            validateAlias(operations.listAliases(properties.requestTimeout()));
            serverVersion = version;
            return version;
        } catch (RuntimeException failure) {
            throw QdrantExceptionMapper.map(
                    failure,
                    VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE,
                    "health check");
        }
    }

    private void initialize() {
        try {
            serverVersion = operations.health(properties.connectTimeout());
            ensureCollection();
            ensurePayloadIndexes();
            ensureAlias();
        } catch (RuntimeException failure) {
            throw QdrantExceptionMapper.map(
                    failure,
                    VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                    "initialization");
        }
    }

    private void ensureCollection() {
        boolean exists = operations.collectionExists(collection, properties.requestTimeout());
        if (!exists) {
            try {
                operations.createCollection(
                        collection,
                        embedding.dimension(),
                        properties.requestTimeout());
            } catch (RuntimeException failure) {
                if (!QdrantExceptionMapper.isAlreadyExists(failure)) {
                    throw failure;
                }
            }
        }
        validateCollection(
                collection,
                operations.getCollection(collection, properties.requestTimeout()));
    }

    private void ensurePayloadIndexes() {
        QdrantCollectionDescriptor descriptor = operations.getCollection(
                collection,
                properties.requestTimeout());
        for (Map.Entry<String, PayloadSchemaType> required : REQUIRED_PAYLOAD_INDEXES.entrySet()) {
            PayloadSchemaType actual = descriptor.payloadIndexes().get(required.getKey());
            if (actual == null) {
                try {
                    operations.createPayloadIndex(
                            collection,
                            required.getKey(),
                            required.getValue(),
                            properties.requestTimeout());
                } catch (RuntimeException failure) {
                    if (!QdrantExceptionMapper.isAlreadyExists(failure)) {
                        throw failure;
                    }
                }
            } else if (actual != required.getValue()) {
                throw configurationError(
                        "Qdrant payload index has incompatible type for field " + required.getKey());
            }
        }
        validateRequiredIndexes(operations.getCollection(collection, properties.requestTimeout()));
    }

    private void ensureAlias() {
        List<QdrantAliasDescriptor> aliases = operations.listAliases(properties.requestTimeout());
        QdrantAliasDescriptor active = findAlias(aliases);
        if (active == null) {
            try {
                operations.createAlias(
                        properties.getCollectionAlias(),
                        collection,
                        properties.requestTimeout());
            } catch (RuntimeException failure) {
                if (!QdrantExceptionMapper.isAlreadyExists(failure)) {
                    throw failure;
                }
            }
            aliases = operations.listAliases(properties.requestTimeout());
        }
        validateAlias(aliases);
    }

    private void validateAlias(List<QdrantAliasDescriptor> aliases) {
        QdrantAliasDescriptor active = findAlias(aliases);
        if (active == null) {
            throw configurationError("Qdrant active collection alias is missing");
        }
        if (!collection.equals(active.collection())) {
            QdrantCollectionDescriptor actual = operations.getCollection(
                    active.collection(),
                    properties.requestTimeout());
            validateCollection(active.collection(), actual);
            throw configurationError("Qdrant active alias points to a different vector space");
        }
    }

    private QdrantAliasDescriptor findAlias(List<QdrantAliasDescriptor> aliases) {
        return aliases.stream()
                .filter(alias -> properties.getCollectionAlias().equals(alias.alias()))
                .findFirst()
                .orElse(null);
    }

    private void validateCollection(String name, QdrantCollectionDescriptor descriptor) {
        if (!descriptor.singleUnnamedVector()) {
            throw configurationError(
                    "Qdrant collection must use exactly one unnamed dense vector: " + name);
        }
        if (descriptor.dimension() != embedding.dimension()) {
            throw configurationError("Qdrant collection dimension is incompatible: " + name);
        }
        if (descriptor.distance() != Distance.Cosine) {
            throw configurationError("Qdrant collection distance must be Cosine: " + name);
        }
    }

    private void validateRequiredIndexes(QdrantCollectionDescriptor descriptor) {
        REQUIRED_PAYLOAD_INDEXES.forEach((field, type) -> {
            if (descriptor.payloadIndexes().get(field) != type) {
                throw configurationError(
                        "Qdrant payload index is missing or incompatible for field " + field);
            }
        });
    }

    private static VectorBackendException configurationError(String message) {
        return new VectorBackendException(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                message,
                false);
    }
}
