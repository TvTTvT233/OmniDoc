package com.javaee.aiservice.rag.vector.qdrant;

import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;

import java.time.Duration;
import java.util.List;

interface QdrantOperations {

    String health(Duration timeout);

    boolean collectionExists(String collection, Duration timeout);

    QdrantCollectionDescriptor getCollection(String collection, Duration timeout);

    void createCollection(String collection, int dimension, Duration timeout);

    List<QdrantAliasDescriptor> listAliases(Duration timeout);

    void createAlias(String alias, String collection, Duration timeout);

    void createPayloadIndex(
            String collection,
            String field,
            PayloadSchemaType schemaType,
            Duration timeout);

    void upsert(String collection, List<PointStruct> points, Duration timeout);

    List<ScoredPoint> query(QueryPoints query, Duration timeout);

    void delete(String collection, List<PointId> pointIds, Duration timeout);

    void delete(String collection, Filter filter, Duration timeout);

    long count(String collection, Filter filter, Duration timeout);
}
