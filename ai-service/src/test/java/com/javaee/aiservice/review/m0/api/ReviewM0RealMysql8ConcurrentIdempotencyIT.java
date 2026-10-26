package com.javaee.aiservice.review.m0.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyClaim;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyClaimStatus;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyRecord;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyResponseSnapshot;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.idempotency.ReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.store.ReviewM0PersistenceProperties;
import com.javaee.aiservice.review.m0.store.ReviewM0SchemaInitializer;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static com.javaee.aiservice.review.m0.api.ReviewM0ApiDtos.CaseResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit real-MySQL gate. The runner executes seed and post-MySQL-restart phases. */
class ReviewM0RealMysql8ConcurrentIdempotencyIT {
    private static final String DIGEST = "a".repeat(64);
    private static final String RESTART_KEY = "restart-replay";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static AppInstance appA;
    private static AppInstance appB;
    private static String phase;

    @BeforeAll
    static void connect() {
        phase = required("REVIEW_M0_MYSQL_PHASE");
        appA = appInstance();
        appB = appInstance();
        assertThat(appA.jdbc().queryForObject("SELECT VERSION()", String.class)).startsWith("8.0.");
        if ("seed".equals(phase)) {
            resetDedicatedDatabase();
            ReviewM0PersistenceProperties properties = new ReviewM0PersistenceProperties();
            properties.setEnabled(true);
            properties.getSchema().getAutoMigration().setEnabled(true);
            new ReviewM0SchemaInitializer(appA.dataSource(), properties).afterPropertiesSet();
            appA.jdbc().execute("""
                    CREATE TABLE idempotency_business_write(
                        business_key VARCHAR(64) PRIMARY KEY,
                        value_text VARCHAR(255) NOT NULL
                    )
                    """);
        } else if (!"verify".equals(phase)) {
            throw new IllegalStateException("REVIEW_M0_MYSQL_PHASE must be seed or verify");
        }
    }

    @AfterAll
    static void cleanAfterVerification() {
        if ("verify".equals(phase) && appA != null) {
            resetDedicatedDatabase();
        }
    }

    @Test
    void configuredRealMysqlPhasePasses() throws Exception {
        if ("seed".equals(phase)) {
            runSeedPhase();
        } else {
            runVerifyPhase();
        }
    }

    private static void runSeedPhase() throws Exception {
        runConcurrentBatch(8, "batch-8");
        runConcurrentBatch(16, "batch-16");
        runConcurrentBatch(48, "batch-48");
        transientCompletionRollsBackBeforeWholeTransactionRetry();
        followerWaitsForCommittedWinnerSnapshot();
        rolledBackWinnerLeavesNoClaimAndAnotherInstanceTakesOver();
        differentPayloadConflictsAndOwnersRemainIsolated();
        seedRestartReplay();
    }

    private static void runConcurrentBatch(int concurrency, String key) throws Exception {
        AtomicInteger operations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<java.util.concurrent.Future<ReviewM0ApiIdempotency.Result<CaseResponse>>> futures =
                    new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                AppInstance app = index % 2 == 0 ? appA : appB;
                futures.add(executor.submit(() -> {
                    start.await();
                    return app.wrapper().execute(
                            scope("owner-concurrent", key), DIGEST, 201, "CASE", CaseResponse.class, () -> {
                                operations.incrementAndGet();
                                app.jdbc().update(
                                        "INSERT INTO idempotency_business_write(business_key,value_text) VALUES (?,?)",
                                        key, "first");
                                LockSupport.parkNanos(25_000_000L);
                                return new ReviewM0ApiIdempotency.Resource<>(
                                        resourceId(key), response(resourceId(key), "first", 0));
                            });
                }));
            }
            start.countDown();
            List<ReviewM0ApiIdempotency.Result<CaseResponse>> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(45, TimeUnit.SECONDS));
            }
            assertThat(results).hasSize(concurrency).allSatisfy(result -> {
                assertThat(result.status()).isEqualTo(201);
                assertThat(result.value()).isEqualTo(results.get(0).value());
            });
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).filteredOn(ReviewM0ApiIdempotency.Result::replayed)
                    .hasSize(concurrency - 1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(operations).hasValue(1);
        assertCounts(key, 1, 1, 1);
    }

    private static void transientCompletionRollsBackBeforeWholeTransactionRetry() {
        String key = "transient-retry";
        JdbcReviewM0IdempotencyStore delegate = appA.store();
        AtomicInteger completions = new AtomicInteger();
        ReviewM0IdempotencyStore injected = new DelegatingStore(delegate) {
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
        ReviewM0ApiIdempotency wrapper = new ReviewM0ApiIdempotency(injected, appA.transactions());

        ReviewM0ApiIdempotency.Result<CaseResponse> result = wrapper.execute(
                scope("owner-concurrent", key), DIGEST, 201, "CASE", CaseResponse.class, () -> {
                    operations.incrementAndGet();
                    appA.jdbc().update(
                            "INSERT INTO idempotency_business_write(business_key,value_text) VALUES (?,?)",
                            key, "first");
                    return new ReviewM0ApiIdempotency.Resource<>(
                            resourceId(key), response(resourceId(key), "first", 0));
                });

        assertThat(result.status()).isEqualTo(201);
        assertThat(operations).hasValue(2);
        assertThat(completions).hasValue(2);
        assertCounts(key, 1, 1, 1);
    }

    private static void followerWaitsForCommittedWinnerSnapshot() throws Exception {
        String key = "follower-wait";
        IdempotencyScope scope = scope("owner-concurrent", key);
        assertThat(appA.store().claim(scope, DIGEST).status()).isEqualTo(IdempotencyClaimStatus.STARTED);
        IdempotencyResponseSnapshot snapshot = IdempotencyResponseSnapshot.capture(
                201, "CASE", resourceId(key),
                JSON.writeValueAsString(response(resourceId(key), "winner", 0)));
        AtomicInteger operations = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var winner = executor.submit(() -> {
                Thread.sleep(100);
                appA.jdbc().update(
                        "INSERT INTO idempotency_business_write(business_key,value_text) VALUES (?,?)",
                        key, "winner");
                appA.store().completeWithSnapshot(
                        scope, DIGEST, 201, "CASE", resourceId(key), snapshot);
                return null;
            });
            ReviewM0ApiIdempotency.Result<CaseResponse> replay = appB.wrapper().execute(
                    scope, DIGEST, 201, "CASE", CaseResponse.class, () -> {
                        operations.incrementAndGet();
                        return new ReviewM0ApiIdempotency.Resource<>(
                                "must-not-run", response("must-not-run", "bad", 1));
                    });
            winner.get(10, TimeUnit.SECONDS);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.value().title()).isEqualTo("winner");
            assertThat(operations).hasValue(0);
        } finally {
            executor.shutdownNow();
        }
        assertCounts(key, 1, 1, 1);
    }

    private static void rolledBackWinnerLeavesNoClaimAndAnotherInstanceTakesOver() {
        String key = "rollback-takeover";
        IdempotencyScope scope = scope("owner-concurrent", key);
        assertThatThrownBy(() -> appA.wrapper().execute(
                scope, DIGEST, 201, "CASE", CaseResponse.class, () -> {
                    appA.jdbc().update(
                            "INSERT INTO idempotency_business_write(business_key,value_text) VALUES (?,?)",
                            key, "rolled-back");
                    throw new IllegalStateException("synthetic winner rollback");
                })).isInstanceOf(IllegalStateException.class);
        assertCounts(key, 0, 0, 0);

        ReviewM0ApiIdempotency.Result<CaseResponse> takeover = appB.wrapper().execute(
                scope, DIGEST, 201, "CASE", CaseResponse.class, () -> {
                    appB.jdbc().update(
                            "INSERT INTO idempotency_business_write(business_key,value_text) VALUES (?,?)",
                            key, "takeover");
                    return new ReviewM0ApiIdempotency.Resource<>(
                            resourceId(key), response(resourceId(key), "takeover", 0));
                });
        assertThat(takeover.replayed()).isFalse();
        assertCounts(key, 1, 1, 1);
    }

    private static void differentPayloadConflictsAndOwnersRemainIsolated() {
        String conflictKey = "payload-conflict";
        create(appA, "owner-a", conflictKey, DIGEST, conflictKey + "-a");
        assertThatThrownBy(() -> create(
                appB, "owner-a", conflictKey, "b".repeat(64), conflictKey + "-bad"))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT);
        create(appB, "owner-b", conflictKey, "b".repeat(64), conflictKey + "-b");
        assertThat(appA.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency WHERE idempotency_key=?",
                Integer.class, conflictKey)).isEqualTo(2);
    }

    private static void seedRestartReplay() {
        create(appA, "owner-restart", RESTART_KEY, DIGEST, RESTART_KEY);
        appA.jdbc().update(
                "UPDATE idempotency_business_write SET value_text='changed' WHERE business_key=?",
                RESTART_KEY);
    }

    private static void runVerifyPhase() {
        AtomicInteger operations = new AtomicInteger();
        ReviewM0ApiIdempotency.Result<CaseResponse> replay = appB.wrapper().execute(
                scope("owner-restart", RESTART_KEY), DIGEST, 201, "CASE", CaseResponse.class, () -> {
                    operations.incrementAndGet();
                    return new ReviewM0ApiIdempotency.Resource<>(
                            "must-not-run", response("must-not-run", "changed", 1));
                });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.value().title()).isEqualTo("first");
        assertThat(replay.value().version()).isZero();
        assertThat(operations).hasValue(0);
        assertCounts(RESTART_KEY, 1, 1, 1);
    }

    private static ReviewM0ApiIdempotency.Result<CaseResponse> create(
            AppInstance app, String owner, String key, String digest, String businessKey
    ) {
        return app.wrapper().execute(
                scope(owner, key), digest, 201, "CASE", CaseResponse.class, () -> {
                    app.jdbc().update(
                            "INSERT INTO idempotency_business_write(business_key,value_text) VALUES (?,?)",
                            businessKey, "first");
                    return new ReviewM0ApiIdempotency.Resource<>(
                            resourceId(businessKey), response(resourceId(businessKey), "first", 0));
                });
    }

    private static void assertCounts(
            String key, int businessCount, int idempotencyCount, int snapshotCount
    ) {
        assertThat(appA.jdbc().queryForObject(
                "SELECT COUNT(*) FROM idempotency_business_write WHERE business_key=?",
                Integer.class, key)).isEqualTo(businessCount);
        assertThat(appA.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency WHERE idempotency_key=?",
                Integer.class, key)).isEqualTo(idempotencyCount);
        assertThat(appA.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot WHERE idempotency_key=?",
                Integer.class, key)).isEqualTo(snapshotCount);
    }

    private static AppInstance appInstance() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(required("REVIEW_M0_MYSQL_JDBC_URL"));
        dataSource.setUser(required("REVIEW_M0_MYSQL_USER"));
        dataSource.setPassword(required("REVIEW_M0_MYSQL_PASSWORD"));
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcReviewM0IdempotencyStore store = new JdbcReviewM0IdempotencyStore(jdbc, transactions);
        return new AppInstance(
                dataSource, jdbc, transactions, store,
                new ReviewM0ApiIdempotency(store, transactions));
    }

    private static void resetDedicatedDatabase() {
        appA.jdbc().execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                List<String> tables = new ArrayList<>();
                try (var rows = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                    while (rows.next()) {
                        tables.add(rows.getString(1));
                    }
                }
                for (String table : tables) {
                    if (!table.matches("review_m0_[a-z0-9_]+|idempotency_business_write")) {
                        throw new AssertionError("Unexpected table in dedicated acceptance database");
                    }
                    statement.execute("DROP TABLE `" + table + "`");
                }
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
                return null;
            } catch (SQLException | RuntimeException | Error error) {
                try (var restore = connection.createStatement()) {
                    restore.execute("SET FOREIGN_KEY_CHECKS=1");
                }
                throw error;
            }
        });
    }

    private static IdempotencyScope scope(String owner, String key) {
        return new IdempotencyScope(owner, "POST", "/api/review-m0/cases", key);
    }

    private static String resourceId(String key) {
        String value = "case-" + key;
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static CaseResponse response(String caseId, String title, long version) {
        Instant time = Instant.parse("2026-08-29T00:00:00Z");
        return new CaseResponse(
                caseId, title, ProcessingMode.LOCAL_ONLY,
                0, 0, 0, 0, 0, version, null, null, time, time);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required; real MySQL acceptance never skips");
        }
        return value;
    }

    private record AppInstance(
            DataSource dataSource,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            JdbcReviewM0IdempotencyStore store,
            ReviewM0ApiIdempotency wrapper
    ) {}

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
}
