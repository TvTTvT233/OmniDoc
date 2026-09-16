package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantCollectionNameFactoryTest {

    @Test
    void includesModelDimensionAndSchemaVersion() {
        String name = new QdrantCollectionNameFactory()
                .create(new EmbeddingSpec("text-embedding-v1", 1536, 2));

        assertThat(name).isEqualTo("omnidoc_chunks_text-embedding-v1_1536_v2");
    }

    @Test
    void sanitizesUnsafeModelNamesWithoutCollidingWithThePlainSlug() {
        QdrantCollectionNameFactory factory = new QdrantCollectionNameFactory();

        String unsafe = factory.create(new EmbeddingSpec("Vendor/Model", 3, 1));
        String plain = factory.create(new EmbeddingSpec("vendor_model", 3, 1));

        assertThat(unsafe).matches("omnidoc_chunks_vendor_model_[0-9a-f]{12}_3_v1");
        assertThat(unsafe).isNotEqualTo(plain);
    }
}
