package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Qdrant connection settings. The API key is intentionally excluded from diagnostics and logs.
 */
@ConfigurationProperties(prefix = "ai.vector.qdrant")
public class QdrantProperties {

    private String host = "qdrant";
    private int grpcPort = 6334;
    private String apiKey = "";
    private String collectionAlias = "omnidoc_chunks_active";
    private long connectTimeoutMs = 3000;
    private long requestTimeoutMs = 5000;
    private int batchSize = 100;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public void setGrpcPort(int grpcPort) {
        this.grpcPort = grpcPort;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getCollectionAlias() {
        return collectionAlias;
    }

    public void setCollectionAlias(String collectionAlias) {
        this.collectionAlias = collectionAlias;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration connectTimeout() {
        return Duration.ofMillis(connectTimeoutMs);
    }

    public Duration requestTimeout() {
        return Duration.ofMillis(requestTimeoutMs);
    }

    public void validate() {
        if (host == null || host.isBlank()) {
            throw configurationError("Qdrant host must not be blank");
        }
        if (grpcPort <= 0 || grpcPort > 65535) {
            throw configurationError("Qdrant gRPC port must be between 1 and 65535");
        }
        if (collectionAlias == null || collectionAlias.isBlank()) {
            throw configurationError("Qdrant collection alias must not be blank");
        }
        if (connectTimeoutMs <= 0 || requestTimeoutMs <= 0) {
            throw configurationError("Qdrant timeouts must be positive");
        }
        if (batchSize <= 0 || batchSize > 1000) {
            throw configurationError("Qdrant batch size must be between 1 and 1000");
        }
        host = host.trim();
        collectionAlias = collectionAlias.trim();
    }

    private static VectorBackendException configurationError(String message) {
        return new VectorBackendException(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                message,
                false);
    }
}
