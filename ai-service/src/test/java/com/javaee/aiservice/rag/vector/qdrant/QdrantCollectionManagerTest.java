package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantCollectionManagerTest {

    private static final EmbeddingSpec EMBEDDING = new EmbeddingSpec("fixture-embedding", 3, 1);

    @Test
    void createsCosineCollectionIndexesAndStableAlias() {
        FakeQdrantOperations operations = new FakeQdrantOperations();

        QdrantCollectionManager manager = manager(operations);

        QdrantCollectionDescriptor descriptor = operations.collections.get(manager.collection());
        assertThat(descriptor.dimension()).isEqualTo(3);
        assertThat(descriptor.distance()).isEqualTo(Distance.Cosine);
        assertThat(descriptor.singleUnnamedVector()).isTrue();
        assertThat(descriptor.payloadIndexes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "userId", PayloadSchemaType.Keyword,
                "knowledgeBaseId", PayloadSchemaType.Keyword,
                "documentId", PayloadSchemaType.Keyword,
                "embeddingModel", PayloadSchemaType.Keyword,
                "schemaVersion", PayloadSchemaType.Integer));
        assertThat(operations.aliases).containsEntry("test_qv02_active", manager.collection());
        assertThat(manager.validateReady()).isEqualTo("1.18.3");
    }

    @Test
    void rejectsExistingCollectionWithWrongDimension() {
        FakeQdrantOperations operations = new FakeQdrantOperations();
        String expected = new QdrantCollectionNameFactory().create(EMBEDDING);
        operations.putCollection(expected, 4, Distance.Cosine, Map.of());

        assertThatThrownBy(() -> manager(operations))
                .isInstanceOfSatisfying(VectorBackendException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(VectorErrorCode.VECTOR_CONFIGURATION_ERROR);
                    assertThat(exception.isRetryable()).isFalse();
                });
    }

    @Test
    void rejectsNonCosineCollection() {
        FakeQdrantOperations operations = new FakeQdrantOperations();
        String expected = new QdrantCollectionNameFactory().create(EMBEDDING);
        operations.putCollection(expected, 3, Distance.Dot, Map.of());

        assertThatThrownBy(() -> manager(operations))
                .isInstanceOfSatisfying(VectorBackendException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(VectorErrorCode.VECTOR_CONFIGURATION_ERROR));
    }

    @Test
    void rejectsAliasThatTargetsAnotherVectorSpace() {
        FakeQdrantOperations operations = new FakeQdrantOperations();
        operations.aliases.put("test_qv02_active", "legacy_collection");
        operations.putCollection("legacy_collection", 4, Distance.Cosine, Map.of());

        assertThatThrownBy(() -> manager(operations))
                .isInstanceOfSatisfying(VectorBackendException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(VectorErrorCode.VECTOR_CONFIGURATION_ERROR));
        assertThat(operations.aliases)
                .containsEntry("test_qv02_active", "legacy_collection");
    }

    @Test
    void doesNotSilentlyReassignCompatibleAliasFromAnotherSchema() {
        FakeQdrantOperations operations = new FakeQdrantOperations();
        operations.aliases.put("test_qv02_active", "legacy_collection");
        operations.putCollection("legacy_collection", 3, Distance.Cosine, Map.of());

        assertThatThrownBy(() -> manager(operations))
                .isInstanceOfSatisfying(VectorBackendException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(VectorErrorCode.VECTOR_CONFIGURATION_ERROR));
        assertThat(operations.aliases)
                .containsEntry("test_qv02_active", "legacy_collection");
    }

    @Test
    void rejectsPayloadIndexWithWrongType() {
        FakeQdrantOperations operations = new FakeQdrantOperations();
        String expected = new QdrantCollectionNameFactory().create(EMBEDDING);
        operations.putCollection(expected, 3, Distance.Cosine,
                Map.of("userId", PayloadSchemaType.Integer));

        assertThatThrownBy(() -> manager(operations))
                .isInstanceOfSatisfying(VectorBackendException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(VectorErrorCode.VECTOR_CONFIGURATION_ERROR));
    }

    private static QdrantCollectionManager manager(FakeQdrantOperations operations) {
        QdrantProperties properties = new QdrantProperties();
        properties.setCollectionAlias("test_qv02_active");
        return new QdrantCollectionManager(
                operations,
                properties,
                EMBEDDING,
                new QdrantCollectionNameFactory());
    }
}
