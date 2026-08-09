package com.javaee.aiservice.rag.vector.qdrant;

import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;

import java.util.Map;

record QdrantCollectionDescriptor(
        long dimension,
        Distance distance,
        boolean singleUnnamedVector,
        Map<String, PayloadSchemaType> payloadIndexes
) {
    QdrantCollectionDescriptor {
        payloadIndexes = payloadIndexes == null ? Map.of() : Map.copyOf(payloadIndexes);
    }
}
