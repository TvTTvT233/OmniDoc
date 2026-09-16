package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "qdrant.integration", matches = "true")
class QdrantVectorBackendIntegrationTest {

    private static final EmbeddingSpec EMBEDDING = new EmbeddingSpec("qv02-fixture", 3, 1);

    @Test
    void validatesIdempotenceTenantIsolationDocumentDeleteAndHealthAgainstQdrant() {
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String alias = "test_qv02_" + runId + "_active";
        String prefix = "test_qv02_" + runId + "_chunks_";
        QdrantIntegrationSupport.Runtime runtime = QdrantIntegrationSupport.open(
                alias,
                prefix,
                EMBEDDING);
        try {
            List<VectorRecord> records = tenantFixture();
            runtime.backend().upsertBatch(records);
            assertThat(runtime.operations().count(
                    alias,
                    null,
                    Duration.ofSeconds(10))).isEqualTo(6);

            runtime.backend().upsert(records.get(0));
            assertThat(runtime.operations().count(
                    alias,
                    null,
                    Duration.ofSeconds(10))).isEqualTo(6);

            for (VectorRecord record : records) {
                List<VectorMatch> matches = runtime.backend().search(new VectorQuery(
                        record.vector(),
                        10,
                        VectorFilter.forKnowledgeBase(
                                record.scope().userId(),
                                record.scope().knowledgeBaseId()),
                        EMBEDDING));
                assertThat(matches)
                        .extracting(VectorMatch::id)
                        .containsExactly(record.id());
            }

            VectorRecord deleted = records.get(0);
            assertThat(runtime.backend().deleteByDocument(
                    deleted.documentId(),
                    deleted.scope())).isEqualTo(1);
            assertThat(runtime.backend().search(new VectorQuery(
                    deleted.vector(),
                    10,
                    VectorFilter.forDocument(
                            deleted.scope().userId(),
                            deleted.scope().knowledgeBaseId(),
                            deleted.documentId()),
                    EMBEDDING))).isEmpty();
            assertThat(runtime.backend().health().available()).isTrue();
        } finally {
            QdrantIntegrationSupport.cleanup(
                    runtime.client(),
                    alias,
                    runtime.manager().collection());
            runtime.close();
        }
    }

    private static List<VectorRecord> tenantFixture() {
        List<VectorRecord> records = new ArrayList<>();
        int position = 0;
        for (String user : List.of("user-a", "user-b", "user-c")) {
            for (String knowledgeBase : List.of("kb-a", "kb-b")) {
                float[] vector = switch (position % 3) {
                    case 0 -> new float[]{1, 0, 0};
                    case 1 -> new float[]{0, 1, 0};
                    default -> new float[]{0, 0, 1};
                };
                records.add(new VectorRecord(
                        "segment-" + position,
                        "document-" + position,
                        vector,
                        new VectorScope(user, knowledgeBase),
                        EMBEDDING,
                        Map.of("fixture", true)));
                position++;
            }
        }
        return records;
    }
}
