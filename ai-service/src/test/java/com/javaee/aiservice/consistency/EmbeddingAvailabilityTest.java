package com.javaee.aiservice.consistency;

import com.javaee.aiservice.rag.DocumentVectorizer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class EmbeddingAvailabilityTest {
    @Test void disabledEmbeddingRejectsBeforeNetwork() {
        DocumentVectorizer vectorizer = new DocumentVectorizer();
        assertThatThrownBy(()->vectorizer.vectorize("test")).hasMessage("EMBEDDING_DISABLED"); vectorizer.shutdown();
    }
    @Test void enabledWithoutCredentialsRejectsBeforeNetwork() {
        DocumentVectorizer vectorizer = new DocumentVectorizer(); ReflectionTestUtils.setField(vectorizer,"enabled",true);
        ReflectionTestUtils.setField(vectorizer,"apiKey","");
        assertThatThrownBy(()->vectorizer.vectorize("test")).hasMessage("EMBEDDING_NOT_CONFIGURED"); vectorizer.shutdown();
    }
}
