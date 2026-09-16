package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV5VectorPointIdFactoryTest {

    private final UuidV5VectorPointIdFactory factory = new UuidV5VectorPointIdFactory();

    @Test
    void producesRfcUuidV5ForTheFrozenNameTuple() {
        UUID pointId = UUID.fromString(factory.create(record("user-a", "kb-a", "segment-1")));

        assertThat(pointId.toString()).isEqualTo("4e458877-6981-5700-95cd-fc20e660a0d4");
        assertThat(pointId.version()).isEqualTo(5);
        assertThat(pointId.variant()).isEqualTo(2);
        assertThat(pointId).isNotEqualTo(UUID.nameUUIDFromBytes(
                "user-a\nkb-a\nsegment-1".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void isStableAndChangesForEitherTenantField() {
        String original = factory.create(record("user-a", "kb-a", "segment-1"));

        assertThat(factory.create(record("user-a", "kb-a", "segment-1"))).isEqualTo(original);
        assertThat(factory.create(record("user-b", "kb-a", "segment-1"))).isNotEqualTo(original);
        assertThat(factory.create(record("user-a", "kb-b", "segment-1"))).isNotEqualTo(original);
    }

    private static VectorRecord record(String userId, String knowledgeBaseId, String id) {
        return new VectorRecord(
                id,
                "document-1",
                new float[]{1, 0, 0},
                new VectorScope(userId, knowledgeBaseId),
                new EmbeddingSpec("fixture-embedding", 3, 1),
                Map.of());
    }
}
