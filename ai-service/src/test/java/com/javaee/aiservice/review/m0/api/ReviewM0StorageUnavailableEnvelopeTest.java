package com.javaee.aiservice.review.m0.api;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;

import static com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0StorageUnavailableEnvelopeTest {

    @Test
    void wrappedConnectionAcquisitionFailureReturnsStable503EnvelopeAndRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/review-m0/cases");
        request.setAttribute(ReviewM0RequestIdFilter.ATTRIBUTE, "req_synthetic_safe");
        Exception failure = new CannotCreateTransactionException(
                "must-not-leak",
                new CannotGetJdbcConnectionException(
                        "must-not-leak", new SQLTransientConnectionException("must-not-leak")));

        var response = new ReviewM0ApiExceptionHandler().internal(failure, request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo(REVIEW_M0_STORAGE_UNAVAILABLE);
        assertThat(response.getBody().requestId()).isEqualTo("req_synthetic_safe");
        assertThat(response.getBody().message()).isEqualTo("Review M0 storage is unavailable");
        assertThat(response.getBody().toString()).doesNotContain("must-not-leak");
    }
}
