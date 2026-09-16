package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.migration.MigrationTargetCounter;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Qdrant implementation of the frozen provider-neutral vector backend contract. */
public final class QdrantVectorBackend implements VectorBackend, MigrationTargetCounter {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorBackend.class);

    private final QdrantOperations operations;
    private final QdrantProperties properties;
    private final QdrantCollectionManager collectionManager;
    private final UuidV5VectorPointIdFactory pointIdFactory;
    private final QdrantPayloadMapper payloadMapper;
    private final QdrantFilterFactory filterFactory;
    private final Duration requestTimeout;

    QdrantVectorBackend(
            QdrantOperations operations,
            QdrantProperties properties,
            QdrantCollectionManager collectionManager,
            UuidV5VectorPointIdFactory pointIdFactory
    ) {
        if (operations == null || properties == null || collectionManager == null
                || pointIdFactory == null) {
            throw new IllegalArgumentException("Qdrant backend dependencies must not be null");
        }
        properties.validate();
        this.operations = operations;
        this.properties = properties;
        this.collectionManager = collectionManager;
        this.pointIdFactory = pointIdFactory;
        this.payloadMapper = new QdrantPayloadMapper();
        this.filterFactory = new QdrantFilterFactory();
        this.requestTimeout = properties.requestTimeout();
    }

    @Override
    public VectorBackendType type() {
        return VectorBackendType.QDRANT;
    }

    @Override
    public void upsert(VectorRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        upsertBatch(List.of(record));
    }

    @Override
    public void upsertBatch(List<VectorRecord> records) {
        if (records == null) {
            throw new IllegalArgumentException("records must not be null");
        }
        if (records.isEmpty()) {
            return;
        }
        long started = System.nanoTime();
        try {
            Map<String, PointStruct> uniquePoints = new LinkedHashMap<>();
            for (VectorRecord record : records) {
                validateRecord(record);
                String pointId = pointIdFactory.create(record);
                uniquePoints.put(pointId, point(record, pointId));
            }
            List<PointStruct> points = new ArrayList<>(uniquePoints.values());
            for (int offset = 0; offset < points.size(); offset += properties.getBatchSize()) {
                int end = Math.min(offset + properties.getBatchSize(), points.size());
                operations.upsert(
                        collectionManager.alias(),
                        List.copyOf(points.subList(offset, end)),
                        requestTimeout);
            }
            log.debug("Qdrant upsert completed: backend={}, alias={}, points={}, elapsedMs={}",
                    type().configValue(),
                    collectionManager.alias(),
                    points.size(),
                    elapsedMillis(started));
        } catch (RuntimeException failure) {
            throw QdrantExceptionMapper.map(
                    failure,
                    VectorErrorCode.VECTOR_UPSERT_FAILED,
                    "upsert");
        }
    }

    @Override
    public List<VectorMatch> search(VectorQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        long started = System.nanoTime();
        try {
            collectionManager.validateEmbedding(query.embedding());
            validateFinite(query.vector(), VectorErrorCode.VECTOR_SEARCH_FAILED);
            Filter filter = filterFactory.forQuery(query.filter());
            QueryPoints request = QueryPoints.newBuilder()
                    .setCollectionName(collectionManager.alias())
                    .setQuery(QueryFactory.nearest(query.vector()))
                    .setFilter(filter)
                    .setLimit(query.topK())
                    .setWithPayload(WithPayloadSelectorFactory.enable(true))
                    .setWithVectors(WithVectorsSelectorFactory.enable(false))
                    .build();
            List<VectorMatch> result = operations.query(request, requestTimeout).stream()
                    .map(this::match)
                    .toList();
            log.debug("Qdrant search completed: backend={}, alias={}, topK={}, "
                            + "filterFields=[userId,knowledgeBaseId,documentId,attributes], "
                            + "matches={}, elapsedMs={}",
                    type().configValue(),
                    collectionManager.alias(),
                    query.topK(),
                    result.size(),
                    elapsedMillis(started));
            return result;
        } catch (RuntimeException failure) {
            throw QdrantExceptionMapper.map(
                    failure,
                    VectorErrorCode.VECTOR_SEARCH_FAILED,
                    "search");
        }
    }

    @Override
    public void deleteById(String id, VectorScope scope) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (scope == null) {
            throw new VectorBackendException(
                    VectorBackendType.QDRANT,
                    VectorErrorCode.VECTOR_FILTER_REQUIRED,
                    "Qdrant tenant filter is required",
                    false);
        }
        try {
            PointId pointId = PointIdFactory.id(UUID.fromString(pointIdFactory.create(id, scope)));
            Filter filter = filterFactory.forId(scope, pointId);
            operations.delete(collectionManager.alias(), filter, requestTimeout);
            log.debug("Qdrant point delete completed: backend={}, alias={}",
                    type().configValue(), collectionManager.alias());
        } catch (RuntimeException failure) {
            throw QdrantExceptionMapper.map(
                    failure,
                    VectorErrorCode.VECTOR_DELETE_FAILED,
                    "delete");
        }
    }

    @Override
    public long deleteByDocument(String documentId, VectorScope scope) {
        try {
            Filter filter = filterFactory.forDocument(scope, documentId);
            long count = operations.count(collectionManager.alias(), filter, requestTimeout);
            if (count > 0) {
                operations.delete(collectionManager.alias(), filter, requestTimeout);
            }
            log.debug("Qdrant document delete completed: backend={}, alias={}, points={}",
                    type().configValue(), collectionManager.alias(), count);
            return count;
        } catch (RuntimeException failure) {
            throw QdrantExceptionMapper.map(
                    failure,
                    VectorErrorCode.VECTOR_DELETE_FAILED,
                    "delete");
        }
    }

    @Override
    public VectorBackendHealth health() {
        try {
            String serverVersion = collectionManager.validateReady();
            return new VectorBackendHealth(
                    type(),
                    true,
                    "ready",
                    Map.of(
                            "alias", collectionManager.alias(),
                            "collection", collectionManager.collection(),
                            "serverVersion", serverVersion));
        } catch (RuntimeException failure) {
            VectorBackendException mapped = QdrantExceptionMapper.map(
                    failure,
                    VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE,
                    "health check");
            return new VectorBackendHealth(
                    type(),
                    false,
                    mapped.getErrorCode().name(),
                    Map.of(
                            "alias", collectionManager.alias(),
                            "collection", collectionManager.collection(),
                            "retryable", mapped.isRetryable()));
        }
    }

    @Override
    public long count(EmbeddingSpec embedding) {
        try {
            collectionManager.validateEmbedding(embedding);
            return operations.count(
                    collectionManager.alias(),
                    Filter.getDefaultInstance(),
                    requestTimeout);
        } catch (RuntimeException failure) {
            throw QdrantExceptionMapper.map(
                    failure,
                    VectorErrorCode.VECTOR_MIGRATION_FAILED,
                    "migration count");
        }
    }

    private PointStruct point(VectorRecord record, String pointId) {
        return PointStruct.newBuilder()
                .setId(PointIdFactory.id(UUID.fromString(pointId)))
                .setVectors(VectorsFactory.vectors(record.vector()))
                .putAllPayload(payloadMapper.toPayload(record))
                .build();
    }

    private VectorMatch match(ScoredPoint point) {
        String id = payloadMapper.requiredString(point.getPayloadMap(), QdrantPayloadMapper.ID);
        String documentId = payloadMapper.requiredString(
                point.getPayloadMap(),
                QdrantPayloadMapper.DOCUMENT_ID);
        return new VectorMatch(
                id,
                documentId,
                point.getScore(),
                payloadMapper.toMetadata(point.getPayloadMap()));
    }

    private void validateRecord(VectorRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("records must not contain null values");
        }
        collectionManager.validateEmbedding(record.embedding());
        validateFinite(record.vector(), VectorErrorCode.VECTOR_UPSERT_FAILED);
    }

    private static void validateFinite(float[] vector, VectorErrorCode errorCode) {
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new VectorBackendException(
                        VectorBackendType.QDRANT,
                        errorCode,
                        "Vector values must be finite",
                        false);
            }
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
