package com.javaee.aiservice.rag.vector.acceptance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Qv04CollectionBoundaryTest {

    @Test
    void acceptsOnlyQv04TestPrefixedNames() {
        assertThat(Qv04CollectionBoundary.requireTestName("qv04_test_20260810_run01"))
                .isEqualTo("qv04_test_20260810_run01");
    }

    @Test
    void rejectsStableProductionAliasAndUnprefixedNames() {
        assertThatThrownBy(() -> Qv04CollectionBoundary.requireTestName("omnidoc_chunks_active"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing non-test");
        assertThatThrownBy(() -> Qv04CollectionBoundary.requireTestName("acceptance_run"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Qv04CollectionBoundary.REQUIRED_PREFIX);
    }

    @Test
    void rejectsBlankAndUnsafeNames() {
        assertThatThrownBy(() -> Qv04CollectionBoundary.requireTestName(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Qv04CollectionBoundary.requireTestName("qv04_test_../prod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe");
    }
}
