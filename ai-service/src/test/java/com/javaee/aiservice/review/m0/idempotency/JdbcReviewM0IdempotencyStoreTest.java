package com.javaee.aiservice.review.m0.idempotency;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReviewM0IdempotencyStoreTest {
    @Test
    void sameCanonicalPayloadReplaysOriginalStatusAndResource() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        JdbcReviewM0IdempotencyStore store =
                new JdbcReviewM0IdempotencyStore(fixture.jdbc(), fixture.transactions());
        IdempotencyScope scope = new IdempotencyScope(
                "owner", "post", "/api/review-m0/cases", "request-1");
        Map<String, Object> firstOrder = new LinkedHashMap<>();
        firstOrder.put("title", "Synthetic");
        firstOrder.put("processingMode", "LOCAL_ONLY");
        Map<String, Object> secondOrder = new LinkedHashMap<>();
        secondOrder.put("processingMode", "LOCAL_ONLY");
        secondOrder.put("title", "Synthetic");
        String firstDigest = IdempotencyDigests.canonicalPayloadDigest(firstOrder);
        String secondDigest = IdempotencyDigests.canonicalPayloadDigest(secondOrder);
        assertThat(secondDigest).isEqualTo(firstDigest);

        assertThat(store.claim(scope, firstDigest).status()).isEqualTo(IdempotencyClaimStatus.STARTED);
        IdempotencyRecord completed = store.complete(scope, firstDigest, 201, "CASE", "case-1");
        IdempotencyClaim replay = store.claim(scope, secondDigest);

        assertThat(replay.status()).isEqualTo(IdempotencyClaimStatus.REPLAY_COMPLETED);
        assertThat(replay.record()).isEqualTo(completed);
        assertThat(replay.record().responseStatus()).isEqualTo(201);
        assertThat(replay.record().responseId()).isEqualTo("case-1");
    }

    @Test
    void sameKeyWithDifferentPayloadHasFrozenConflictSemantic() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        JdbcReviewM0IdempotencyStore store =
                new JdbcReviewM0IdempotencyStore(fixture.jdbc(), fixture.transactions());
        IdempotencyScope scope = new IdempotencyScope("owner", "POST", "/fixed", "key");
        store.claim(scope, "a".repeat(64));

        assertThatThrownBy(() -> store.claim(scope, "b".repeat(64)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT);
    }

    @Test
    void concurrentClaimsHaveExactlyOneCreator() throws Exception {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        JdbcReviewM0IdempotencyStore store =
                new JdbcReviewM0IdempotencyStore(fixture.jdbc(), fixture.transactions());
        IdempotencyScope scope = new IdempotencyScope("owner", "POST", "/fixed", "race");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<IdempotencyClaimStatus>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return store.claim(scope, "c".repeat(64)).status();
                }));
            }
            start.countDown();
            List<IdempotencyClaimStatus> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get());
            }
            assertThat(results).filteredOn(status -> status == IdempotencyClaimStatus.STARTED).hasSize(1);
            assertThat(results).filteredOn(status -> status == IdempotencyClaimStatus.REPLAY_IN_PROGRESS).hasSize(7);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void claimJoinsCallerTransactionSoFailedResourceCreationLeavesNoRecord() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        JdbcReviewM0IdempotencyStore store =
                new JdbcReviewM0IdempotencyStore(fixture.jdbc(), fixture.transactions());
        IdempotencyScope scope = new IdempotencyScope("owner", "POST", "/fixed", "rollback");

        assertThatThrownBy(() -> new TransactionTemplate(fixture.transactions()).executeWithoutResult(status -> {
            store.claim(scope, "d".repeat(64));
            throw new IllegalStateException("resource creation failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.find(scope)).isEmpty();
    }
}
