package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewM0JobHandlerRegistryTest {
    @Test
    void handlersAreRegisteredByFrozenJobTypeWithoutAgentRegistry() {
        ReviewM0JobHandler handler = handler(ReviewJobType.FORMAL_REVIEW);
        ReviewM0JobHandlerRegistry registry = new ReviewM0JobHandlerRegistry(List.of(handler));

        assertThat(registry.find(ReviewJobType.FORMAL_REVIEW)).contains(handler);
        assertThat(registry.find(ReviewJobType.CHECKLIST_EXTRACTION)).isEmpty();
    }

    @Test
    void duplicateHandlerRegistrationFailsFast() {
        assertThatThrownBy(() -> new ReviewM0JobHandlerRegistry(List.of(
                handler(ReviewJobType.FORMAL_REVIEW), handler(ReviewJobType.FORMAL_REVIEW))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FORMAL_REVIEW");
    }

    private static ReviewM0JobHandler handler(ReviewJobType type) {
        return new ReviewM0JobHandler() {
            @Override
            public ReviewJobType jobType() {
                return type;
            }

            @Override
            public void handle(ReviewJob leasedJob, ReviewM0JobStore jobStore) {
                // No concrete M0-03/M0-05 handler belongs to M0-02.
            }
        };
    }
}
