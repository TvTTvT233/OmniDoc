package com.javaee.aiservice.rag.vector.qdrant;

import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FakeQdrantOperations implements QdrantOperations {

    final Map<String, QdrantCollectionDescriptor> collections = new LinkedHashMap<>();
    final Map<String, String> aliases = new LinkedHashMap<>();
    final Map<String, PointStruct> storedPoints = new LinkedHashMap<>();
    final List<Integer> upsertBatchSizes = new ArrayList<>();
    final List<String> createdIndexes = new ArrayList<>();
    QueryPoints lastQuery;
    Filter lastDeleteFilter;
    long countResult;
    String healthVersion = "1.18.3";
    List<ScoredPoint> queryResults = List.of();
    String failingOperation;
    RuntimeException failure;

    @Override
    public String health(Duration timeout) {
        maybeFail("health");
        return healthVersion;
    }

    @Override
    public boolean collectionExists(String collection, Duration timeout) {
        maybeFail("collectionExists");
        return collections.containsKey(collection);
    }

    @Override
    public QdrantCollectionDescriptor getCollection(String collection, Duration timeout) {
        maybeFail("getCollection");
        QdrantCollectionDescriptor descriptor = collections.get(collection);
        if (descriptor == null) {
            throw new IllegalStateException("fixture collection is missing");
        }
        return descriptor;
    }

    @Override
    public void createCollection(String collection, int dimension, Duration timeout) {
        maybeFail("createCollection");
        collections.put(collection, new QdrantCollectionDescriptor(
                dimension,
                Distance.Cosine,
                true,
                Map.of()));
    }

    @Override
    public List<QdrantAliasDescriptor> listAliases(Duration timeout) {
        maybeFail("listAliases");
        return aliases.entrySet().stream()
                .map(entry -> new QdrantAliasDescriptor(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public void createAlias(String alias, String collection, Duration timeout) {
        maybeFail("createAlias");
        aliases.put(alias, collection);
    }

    @Override
    public void createPayloadIndex(
            String collection,
            String field,
            PayloadSchemaType schemaType,
            Duration timeout
    ) {
        maybeFail("createPayloadIndex");
        QdrantCollectionDescriptor existing = collections.get(collection);
        Map<String, PayloadSchemaType> indexes = new LinkedHashMap<>(existing.payloadIndexes());
        indexes.put(field, schemaType);
        collections.put(collection, new QdrantCollectionDescriptor(
                existing.dimension(),
                existing.distance(),
                existing.singleUnnamedVector(),
                indexes));
        createdIndexes.add(field);
    }

    @Override
    public void upsert(String collection, List<PointStruct> points, Duration timeout) {
        maybeFail("upsert");
        upsertBatchSizes.add(points.size());
        points.forEach(point -> storedPoints.put(point.getId().getUuid(), point));
    }

    @Override
    public List<ScoredPoint> query(QueryPoints query, Duration timeout) {
        maybeFail("query");
        lastQuery = query;
        return queryResults;
    }

    @Override
    public void delete(String collection, List<PointId> pointIds, Duration timeout) {
        maybeFail("deleteIds");
        pointIds.forEach(pointId -> storedPoints.remove(pointId.getUuid()));
    }

    @Override
    public void delete(String collection, Filter filter, Duration timeout) {
        maybeFail("deleteFilter");
        lastDeleteFilter = filter;
    }

    @Override
    public long count(String collection, Filter filter, Duration timeout) {
        maybeFail("count");
        return countResult;
    }

    void putCollection(
            String collection,
            long dimension,
            Distance distance,
            Map<String, PayloadSchemaType> indexes
    ) {
        collections.put(collection, new QdrantCollectionDescriptor(
                dimension,
                distance,
                true,
                indexes));
    }

    void failNext(String operation, RuntimeException exception) {
        failingOperation = operation;
        failure = exception;
    }

    private void maybeFail(String operation) {
        if (operation.equals(failingOperation)) {
            failingOperation = null;
            RuntimeException toThrow = failure;
            failure = null;
            throw toThrow;
        }
    }
}
