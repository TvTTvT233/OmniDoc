package com.javaee.aiservice.rag.vector.acceptance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Qv04MetricsTest {

    @Test
    void calculatesRecallMrrAndOverlapAtKByLogicalRecordId() {
        List<String> expected = List.of("record-a", "record-b");
        List<String> baseline = List.of("record-x", "record-a", "record-b", "record-y", "record-z");
        List<String> qdrant = List.of("record-a", "record-y", "record-b", "record-q", "record-z");

        assertThat(VectorBackendAcceptanceHarness.recallAtK(expected, baseline, 5)).isEqualTo(1.0d);
        assertThat(VectorBackendAcceptanceHarness.reciprocalRank(expected, baseline)).isEqualTo(0.5d);
        assertThat(VectorBackendAcceptanceHarness.overlapAtK(baseline, qdrant, 5)).isEqualTo(0.8d);
    }

    @Test
    void calculatesNearestRankP50AndP95() {
        List<Long> samples = List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1_000L);

        assertThat(VectorBackendAcceptanceHarness.percentile(samples, 0.50d)).isEqualTo(500L);
        assertThat(VectorBackendAcceptanceHarness.percentile(samples, 0.95d)).isEqualTo(1_000L);
    }

    @Test
    void rejectsUndefinedMetricInputs() {
        assertThatThrownBy(() -> VectorBackendAcceptanceHarness.recallAtK(List.of(), List.of(), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorBackendAcceptanceHarness.percentile(List.of(1L), 0.0d))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
