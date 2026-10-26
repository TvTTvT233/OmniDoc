package com.javaee.aiservice.review.m0.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0KeyDigestsTest {
    @Test
    void lengthPrefixesKeepEveryCompositeBoundaryUnambiguous() {
        String first = ReviewM0KeyDigests.idempotencyScope("ab", "C", "/d", "e");
        String second = ReviewM0KeyDigests.idempotencyScope("a", "BC", "/d", "e");
        String third = ReviewM0KeyDigests.idempotencyScope("ab", "C", "/", "de");

        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(first).isNotEqualTo(second).isNotEqualTo(third);
        assertThat(ReviewM0KeyDigests.idempotencyScope("ab", "C", "/d", "e"))
                .isEqualTo(first);
    }

    @Test
    void digestDomainsSeparateZipPathsFromHttpScopesAndUseUtf8ByteLengths() {
        assertThat(ReviewM0KeyDigests.zipEntryScope("源", "目录/材料.pdf"))
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(ReviewM0KeyDigests.idempotencyScope(
                        "源", "目录/材料.pdf", "目录/材料.pdf", "目录/材料.pdf"));
    }
}
