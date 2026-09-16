package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantPersistenceIntegrationTest {

    private static final String ALIAS = "test_qv02_persistence_active";
    private static final String PREFIX = "test_qv02_persistence_chunks_";
    private static final EmbeddingSpec EMBEDDING = new EmbeddingSpec("persistence-fixture", 3, 1);

    @Test
    void executeRequestedPersistencePhase() {
        Assumptions.assumeTrue(Boolean.getBoolean("qdrant.integration"));
        String phase = System.getProperty("qdrant.persistence.phase", "");
        Assumptions.assumeTrue(Set.of("write", "read", "cleanup").contains(phase));

        if ("cleanup".equals(phase)) {
            try (QdrantClient client = QdrantIntegrationSupport.client(ALIAS)) {
                QdrantIntegrationSupport.cleanup(client, ALIAS, collection());
            }
            return;
        }

        try (QdrantIntegrationSupport.Runtime runtime = QdrantIntegrationSupport.open(
                ALIAS,
                PREFIX,
                EMBEDDING)) {
            if ("write".equals(phase)) {
                runtime.backend().upsert(record());
                assertThat(runtime.backend().search(query()))
                        .extracting(match -> match.id())
                        .containsExactly("persistence-segment");
            } else {
                assertThat(runtime.backend().search(query()))
                        .extracting(match -> match.id())
                        .containsExactly("persistence-segment");
            }
        }
    }

    private static String collection() {
        return new QdrantCollectionNameFactory(PREFIX).create(EMBEDDING);
    }

    private static VectorRecord record() {
        return new VectorRecord(
                "persistence-segment",
                "persistence-document",
                new float[]{1, 0, 0},
                new VectorScope("persistence-user", "persistence-kb"),
                EMBEDDING,
                Map.of("fixture", "restart"));
    }

    private static VectorQuery query() {
        return new VectorQuery(
                new float[]{1, 0, 0},
                3,
                VectorFilter.forKnowledgeBase("persistence-user", "persistence-kb"),
                EMBEDDING);
    }
}
