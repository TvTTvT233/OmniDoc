package com.javaee.aiservice.rag.vector.qdrant;

import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.AliasDescription;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Blocking adapter around the official asynchronous Qdrant Java client. */
final class OfficialQdrantOperations implements QdrantOperations {

    private static final long FUTURE_COMPLETION_GRACE_MS = 250;
    private final QdrantClient client;

    OfficialQdrantOperations(QdrantClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        this.client = client;
    }

    @Override
    public String health(Duration timeout) {
        return await(client.healthCheckAsync(timeout), timeout).getVersion();
    }

    @Override
    public boolean collectionExists(String collection, Duration timeout) {
        return await(client.collectionExistsAsync(collection, timeout), timeout);
    }

    @Override
    public QdrantCollectionDescriptor getCollection(String collection, Duration timeout) {
        CollectionInfo info = await(client.getCollectionInfoAsync(collection, timeout), timeout);
        VectorsConfig vectors = info.getConfig().getParams().getVectorsConfig();
        boolean singleUnnamedVector = vectors.hasParams();
        long dimension = singleUnnamedVector ? vectors.getParams().getSize() : -1;
        Distance distance = singleUnnamedVector
                ? vectors.getParams().getDistance()
                : Distance.UNRECOGNIZED;
        Map<String, PayloadSchemaType> payloadIndexes = new LinkedHashMap<>();
        info.getPayloadSchemaMap().forEach(
                (field, schema) -> payloadIndexes.put(field, schema.getDataType()));
        return new QdrantCollectionDescriptor(
                dimension,
                distance,
                singleUnnamedVector,
                payloadIndexes);
    }

    @Override
    public void createCollection(String collection, int dimension, Duration timeout) {
        VectorParams params = VectorParams.newBuilder()
                .setSize(dimension)
                .setDistance(Distance.Cosine)
                .build();
        await(client.createCollectionAsync(collection, params, timeout), timeout);
    }

    @Override
    public List<QdrantAliasDescriptor> listAliases(Duration timeout) {
        List<AliasDescription> aliases = await(client.listAliasesAsync(timeout), timeout);
        return aliases.stream()
                .map(alias -> new QdrantAliasDescriptor(
                        alias.getAliasName(),
                        alias.getCollectionName()))
                .toList();
    }

    @Override
    public void createAlias(String alias, String collection, Duration timeout) {
        await(client.createAliasAsync(alias, collection, timeout), timeout);
    }

    @Override
    public void createPayloadIndex(
            String collection,
            String field,
            PayloadSchemaType schemaType,
            Duration timeout
    ) {
        await(client.createPayloadIndexAsync(
                collection,
                field,
                schemaType,
                null,
                true,
                null,
                timeout), timeout);
    }

    @Override
    public void upsert(String collection, List<PointStruct> points, Duration timeout) {
        await(client.upsertAsync(collection, points, timeout), timeout);
    }

    @Override
    public List<ScoredPoint> query(QueryPoints query, Duration timeout) {
        return await(client.queryAsync(query, timeout), timeout);
    }

    @Override
    public void delete(String collection, List<PointId> pointIds, Duration timeout) {
        await(client.deleteAsync(collection, pointIds, timeout), timeout);
    }

    @Override
    public void delete(String collection, Filter filter, Duration timeout) {
        await(client.deleteAsync(collection, filter, timeout), timeout);
    }

    @Override
    public long count(String collection, Filter filter, Duration timeout) {
        return await(client.countAsync(collection, filter, true, timeout), timeout);
    }

    private static <T> T await(ListenableFuture<T> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis() + FUTURE_COMPLETION_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QdrantClientCallException(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new QdrantClientCallException(cause);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new QdrantClientCallException(exception);
        }
    }
}
