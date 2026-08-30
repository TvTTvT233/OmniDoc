package com.javaee.aiservice.review.m0.api;

import com.javaee.aiservice.review.m0.idempotency.IdempotencyResponseSnapshot;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.idempotency.ReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyClaim;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyRecord;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.javaee.aiservice.review.m0.api.ReviewM0ApiDtos.CaseResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewM0ApiIdempotencySnapshotTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void replayAfterResourceMutationAndSimulatedRestartReturnsFirstStatusAndEveryField() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        IdempotencyScope scope = scope("restart");
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<CaseResponse> mutableResource = new AtomicReference<>(response("first", 0));

        ReviewM0ApiIdempotency firstProcess = wrapper(fixture);
        ReviewM0ApiIdempotency.Result<CaseResponse> first = firstProcess.execute(
                scope, DIGEST, 201, "CASE", CaseResponse.class,
                () -> {
                    writes.incrementAndGet();
                    return new ReviewM0ApiIdempotency.Resource<>("case-1", mutableResource.get());
                });

        mutableResource.set(response("changed-after-success", 9));
        ReviewM0ApiIdempotency restartedProcess = wrapper(fixture);
        ReviewM0ApiIdempotency.Result<CaseResponse> replay = restartedProcess.execute(
                scope, DIGEST, 201, "CASE", CaseResponse.class,
                () -> {
                    writes.incrementAndGet();
                    return new ReviewM0ApiIdempotency.Resource<>("case-1", mutableResource.get());
                });

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.value()).isEqualTo(first.value());
        assertThat(replay.value().title()).isEqualTo("first");
        assertThat(replay.value().version()).isZero();
        assertThat(writes).hasValue(1);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentCanonicalClientPayloadRemainsConflict() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0ApiIdempotency wrapper = wrapper(fixture);
        IdempotencyScope scope = scope("different-payload");
        wrapper.execute(scope, DIGEST, 201, "CASE", CaseResponse.class,
                () -> new ReviewM0ApiIdempotency.Resource<>("case-1", response("first", 0)));

        assertThatThrownBy(() -> wrapper.execute(
                scope, "b".repeat(64), 201, "CASE", CaseResponse.class,
                () -> new ReviewM0ApiIdempotency.Resource<>("case-2", response("second", 0))))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT);
    }

    @Test
    void concurrentClaimsProduceOneBusinessWriteAndOneSnapshot() throws Exception {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        fixture.jdbc().execute("CREATE TABLE idempotency_business_write(id VARCHAR(64) PRIMARY KEY)");
        ReviewM0ApiIdempotency wrapper = wrapper(fixture);
        IdempotencyScope scope = scope("concurrent");
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<ReviewM0ApiIdempotency.Result<CaseResponse>>> futures =
                    new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return wrapper.execute(scope, DIGEST, 201, "CASE", CaseResponse.class, () -> {
                        writes.incrementAndGet();
                        fixture.jdbc().update(
                                "INSERT INTO idempotency_business_write(id) VALUES ('case-1')");
                        return new ReviewM0ApiIdempotency.Resource<>("case-1", response("first", 0));
                    });
                }));
            }
            start.countDown();
            List<ReviewM0ApiIdempotency.Result<CaseResponse>> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get());
            }
            assertThat(results).hasSize(8).allMatch(result -> result.status() == 201);
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).filteredOn(ReviewM0ApiIdempotency.Result::replayed).hasSize(7);
        } finally {
            executor.shutdownNow();
        }
        assertThat(writes).hasValue(1);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM idempotency_business_write", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot", Integer.class)).isEqualTo(1);
    }

    @Test
    void transientFailureAfterCompletionRetriesTheWholeWriteInANewTransaction() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        fixture.jdbc().execute("CREATE TABLE retry_business_write(id VARCHAR(64) PRIMARY KEY)");
        JdbcReviewM0IdempotencyStore delegate = store(fixture);
        AtomicInteger completions = new AtomicInteger();
        ReviewM0IdempotencyStore failFirstCompletion = new DelegatingStore(delegate) {
            @Override
            public IdempotencyRecord completeWithSnapshot(
                    IdempotencyScope scope, String requestDigest, int responseStatus,
                    String responseType, String responseId, IdempotencyResponseSnapshot snapshot
            ) {
                IdempotencyRecord completed = super.completeWithSnapshot(
                        scope, requestDigest, responseStatus, responseType, responseId, snapshot);
                if (completions.incrementAndGet() == 1) {
                    throw new CannotAcquireLockException("synthetic transient contention");
                }
                return completed;
            }
        };
        AtomicInteger operations = new AtomicInteger();

        ReviewM0ApiIdempotency.Result<CaseResponse> result = new ReviewM0ApiIdempotency(
                failFirstCompletion, fixture.transactions()).execute(
                scope("whole-transaction-retry"), DIGEST, 201, "CASE", CaseResponse.class, () -> {
                    operations.incrementAndGet();
                    fixture.jdbc().update(
                            "INSERT INTO retry_business_write(id) VALUES ('case-1')");
                    return new ReviewM0ApiIdempotency.Resource<>(
                            "case-1", response("first", 0));
                });

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(operations).hasValue(2);
        assertThat(completions).hasValue(2);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM retry_business_write", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot", Integer.class)).isEqualTo(1);
    }

    @Test
    void transientRetryEndsTheFailedWholeTransactionBeforeStartingTheNextAttempt() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        fixture.jdbc().execute("CREATE TABLE retry_transaction_proof(id VARCHAR(64) PRIMARY KEY)");
        JdbcReviewM0IdempotencyStore delegate = store(fixture);
        AtomicInteger completions = new AtomicInteger();
        ReviewM0IdempotencyStore failFirstCompletion = new DelegatingStore(delegate) {
            @Override
            public IdempotencyRecord completeWithSnapshot(
                    IdempotencyScope scope, String requestDigest, int responseStatus,
                    String responseType, String responseId, IdempotencyResponseSnapshot snapshot
            ) {
                IdempotencyRecord completed = super.completeWithSnapshot(
                        scope, requestDigest, responseStatus, responseType, responseId, snapshot);
                if (completions.incrementAndGet() == 1) {
                    throw new CannotAcquireLockException("synthetic transient contention");
                }
                return completed;
            }
        };
        List<Integer> transactionCompletions = new ArrayList<>();
        AtomicInteger operations = new AtomicInteger();

        ReviewM0ApiIdempotency.Result<CaseResponse> result = new ReviewM0ApiIdempotency(
                failFirstCompletion, fixture.transactions()).execute(
                scope("transaction-boundary-proof"), DIGEST, 201, "CASE", CaseResponse.class, () -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCompletion(int status) {
                                    transactionCompletions.add(status);
                                }
                            });
                    operations.incrementAndGet();
                    fixture.jdbc().update(
                            "INSERT INTO retry_transaction_proof(id) VALUES ('case-1')");
                    return new ReviewM0ApiIdempotency.Resource<>(
                            "case-1", response("first", 0));
                });

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(operations).hasValue(2);
        assertThat(completions).hasValue(2);
        assertThat(transactionCompletions).containsExactly(
                TransactionSynchronization.STATUS_ROLLED_BACK,
                TransactionSynchronization.STATUS_COMMITTED);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM retry_transaction_proof", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot", Integer.class)).isEqualTo(1);
    }

    @Test
    void followerWaitsAcrossTransactionsUntilWinnerCompletesAndNeverRunsBusinessOperation()
            throws Exception {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        JdbcReviewM0IdempotencyStore store = store(fixture);
        IdempotencyScope scope = scope("bounded-follower");
        assertThat(store.claim(scope, DIGEST).status())
                .isEqualTo(com.javaee.aiservice.review.m0.idempotency.IdempotencyClaimStatus.STARTED);
        String body = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(response("winner", 0));
        IdempotencyResponseSnapshot snapshot = IdempotencyResponseSnapshot.capture(
                201, "CASE", "case-1", body);
        AtomicInteger operations = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var winner = executor.submit(() -> {
                Thread.sleep(75);
                store.completeWithSnapshot(scope, DIGEST, 201, "CASE", "case-1", snapshot);
                return null;
            });
            ReviewM0ApiIdempotency.Result<CaseResponse> replay = wrapper(fixture).execute(
                    scope, DIGEST, 201, "CASE", CaseResponse.class, () -> {
                        operations.incrementAndGet();
                        return new ReviewM0ApiIdempotency.Resource<>(
                                "case-2", response("must-not-run", 1));
                    });
            winner.get();

            assertThat(replay.replayed()).isTrue();
            assertThat(replay.status()).isEqualTo(201);
            assertThat(replay.value().title()).isEqualTo("winner");
            assertThat(operations).hasValue(0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exhaustedTransientCompetitionUsesStableStorageUnavailableContract() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        JdbcReviewM0IdempotencyStore delegate = store(fixture);
        AtomicInteger attempts = new AtomicInteger();
        ReviewM0IdempotencyStore unavailable = new DelegatingStore(delegate) {
            @Override
            public IdempotencyClaim claim(IdempotencyScope scope, String requestDigest) {
                attempts.incrementAndGet();
                throw new CannotAcquireLockException("synthetic transient contention");
            }
        };

        assertStorageUnavailable(() -> new ReviewM0ApiIdempotency(
                unavailable, fixture.transactions()).execute(
                scope("exhausted"), DIGEST, 201, "CASE", CaseResponse.class,
                () -> new ReviewM0ApiIdempotency.Resource<>(
                        "case-1", response("must-not-run", 0))));
        assertThat(attempts).hasValue(24);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency", Integer.class)).isZero();
    }

    @Test
    void operationFailureAndSnapshotLimitFailureRollBackBusinessClaimAndSnapshot() {
        ReviewM0JdbcTestFixture operationFixture = new ReviewM0JdbcTestFixture();
        operationFixture.jdbc().execute("CREATE TABLE rollback_business_write(id VARCHAR(64) PRIMARY KEY)");
        ReviewM0ApiIdempotency operationWrapper = wrapper(operationFixture);
        assertThatThrownBy(() -> operationWrapper.execute(
                scope("operation-rollback"), DIGEST, 201, "CASE", CaseResponse.class, () -> {
                    operationFixture.jdbc().update(
                            "INSERT INTO rollback_business_write(id) VALUES ('case-1')");
                    throw new IllegalStateException("synthetic rollback");
                })).isInstanceOf(IllegalStateException.class);
        assertEmptyTransaction(operationFixture, "rollback_business_write");

        ReviewM0JdbcTestFixture oversizeFixture = new ReviewM0JdbcTestFixture();
        oversizeFixture.jdbc().execute("CREATE TABLE rollback_business_write(id VARCHAR(64) PRIMARY KEY)");
        ReviewM0ApiIdempotency oversizeWrapper = wrapper(oversizeFixture);
        String oversize = "x".repeat(IdempotencyResponseSnapshot.MAX_RESPONSE_BODY_BYTES + 1);
        assertStorageUnavailable(() -> oversizeWrapper.execute(
                scope("oversize-create"), DIGEST, 201, "CASE", CaseResponse.class, () -> {
                    oversizeFixture.jdbc().update(
                            "INSERT INTO rollback_business_write(id) VALUES ('case-1')");
                    return new ReviewM0ApiIdempotency.Resource<>(
                            "case-1", response(oversize, 0));
                }));
        assertEmptyTransaction(oversizeFixture, "rollback_business_write");
    }

    @Test
    void tamperedUnknownVersionOversizedAndLegacySnapshotsAllFailClosed() {
        ReviewM0JdbcTestFixture tampered = completed("tampered");
        tampered.jdbc().update(
                "UPDATE review_m0_idempotency_response_snapshot SET response_body='{}'");
        assertReplayFailsClosed(tampered, "tampered");

        ReviewM0JdbcTestFixture unknown = completed("unknown-version");
        unknown.jdbc().execute("ALTER TABLE review_m0_idempotency_response_snapshot "
                + "DROP CONSTRAINT ck_review_m0_idempotency_snapshot_format");
        unknown.jdbc().update(
                "UPDATE review_m0_idempotency_response_snapshot SET format_version=99");
        assertReplayFailsClosed(unknown, "unknown-version");

        ReviewM0JdbcTestFixture oversized = completed("oversized-persisted");
        oversized.jdbc().execute("ALTER TABLE review_m0_idempotency_response_snapshot "
                + "DROP CONSTRAINT ck_review_m0_idempotency_snapshot_size");
        oversized.jdbc().update(
                "UPDATE review_m0_idempotency_response_snapshot SET response_body_bytes=?",
                IdempotencyResponseSnapshot.MAX_RESPONSE_BODY_BYTES + 1L);
        assertReplayFailsClosed(oversized, "oversized-persisted");

        ReviewM0JdbcTestFixture legacy = new ReviewM0JdbcTestFixture();
        JdbcReviewM0IdempotencyStore legacyStore = store(legacy);
        IdempotencyScope legacyScope = scope("legacy");
        legacyStore.claim(legacyScope, DIGEST);
        legacyStore.complete(legacyScope, DIGEST, 201, "CASE", "case-1");
        assertReplayFailsClosed(legacy, "legacy");
    }

    @Test
    void forbiddenInternalResponseFieldRollsBackInsteadOfPersistingIt() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        fixture.jdbc().execute("CREATE TABLE rollback_business_write(id VARCHAR(64) PRIMARY KEY)");
        ReviewM0ApiIdempotency wrapper = wrapper(fixture);
        assertStorageUnavailable(() -> wrapper.execute(
                scope("forbidden-field"), DIGEST, 201, "UNSAFE", UnsafeResponse.class, () -> {
                    fixture.jdbc().update(
                            "INSERT INTO rollback_business_write(id) VALUES ('unsafe-1')");
                    return new ReviewM0ApiIdempotency.Resource<>(
                            "unsafe-1", new UnsafeResponse("internal/path"));
                }));
        assertEmptyTransaction(fixture, "rollback_business_write");
    }

    private static ReviewM0JdbcTestFixture completed(String key) {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        wrapper(fixture).execute(scope(key), DIGEST, 201, "CASE", CaseResponse.class,
                () -> new ReviewM0ApiIdempotency.Resource<>("case-1", response("first", 0)));
        return fixture;
    }

    private static void assertReplayFailsClosed(ReviewM0JdbcTestFixture fixture, String key) {
        AtomicInteger writes = new AtomicInteger();
        assertStorageUnavailable(() -> wrapper(fixture).execute(
                scope(key), DIGEST, 201, "CASE", CaseResponse.class, () -> {
                    writes.incrementAndGet();
                    return new ReviewM0ApiIdempotency.Resource<>(
                            "case-1", response("must-not-run", 1));
                }));
        assertThat(writes).hasValue(0);
    }

    private static void assertStorageUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE);
    }

    private static void assertEmptyTransaction(ReviewM0JdbcTestFixture fixture, String businessTable) {
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + businessTable, Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot", Integer.class)).isZero();
    }

    private static ReviewM0ApiIdempotency wrapper(ReviewM0JdbcTestFixture fixture) {
        return new ReviewM0ApiIdempotency(store(fixture), fixture.transactions());
    }

    private static JdbcReviewM0IdempotencyStore store(ReviewM0JdbcTestFixture fixture) {
        return new JdbcReviewM0IdempotencyStore(fixture.jdbc(), fixture.transactions());
    }

    private static class DelegatingStore implements ReviewM0IdempotencyStore {
        private final ReviewM0IdempotencyStore delegate;

        private DelegatingStore(ReviewM0IdempotencyStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public IdempotencyClaim claim(IdempotencyScope scope, String requestDigest) {
            return delegate.claim(scope, requestDigest);
        }

        @Override
        public IdempotencyRecord complete(
                IdempotencyScope scope, String requestDigest, int responseStatus,
                String responseType, String responseId
        ) {
            return delegate.complete(scope, requestDigest, responseStatus, responseType, responseId);
        }

        @Override
        public IdempotencyRecord completeWithSnapshot(
                IdempotencyScope scope, String requestDigest, int responseStatus,
                String responseType, String responseId, IdempotencyResponseSnapshot snapshot
        ) {
            return delegate.completeWithSnapshot(
                    scope, requestDigest, responseStatus, responseType, responseId, snapshot);
        }

        @Override
        public IdempotencyRecord attachSnapshotToCompletedInCallerTransaction(
                IdempotencyScope scope, String requestDigest, int responseStatus,
                String responseType, String responseId, IdempotencyResponseSnapshot snapshot
        ) {
            return delegate.attachSnapshotToCompletedInCallerTransaction(
                    scope, requestDigest, responseStatus, responseType, responseId, snapshot);
        }

        @Override
        public Optional<IdempotencyRecord> find(IdempotencyScope scope) {
            return delegate.find(scope);
        }
    }

    private static IdempotencyScope scope(String key) {
        return new IdempotencyScope(
                "owner-a", "POST", "/api/review-m0/cases/case-a/resource", key);
    }

    private static CaseResponse response(String title, long version) {
        Instant created = Instant.parse("2026-08-23T00:00:00Z");
        return new CaseResponse(
                "case-1", title, ProcessingMode.LOCAL_ONLY,
                1, 2, 3, 4, 5, version, "snapshot-1", "report-1", created, created);
    }

    private record UnsafeResponse(String objectKey) {}
}
