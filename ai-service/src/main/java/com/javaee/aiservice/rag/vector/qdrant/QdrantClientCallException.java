package com.javaee.aiservice.rag.vector.qdrant;

final class QdrantClientCallException extends RuntimeException {

    QdrantClientCallException(Throwable cause) {
        super("Qdrant client call failed", cause);
    }
}
