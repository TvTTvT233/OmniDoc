package com.javaee.aiservice.review.m0.api;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.ReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReviewM0BoundedStorageFailureTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void startupDatabaseUnavailableFailsOnceWithinBound() {
        ReviewM0IdempotencyStore store = mock(ReviewM0IdempotencyStore.class);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(
                new AlwaysUnavailableDataSource());

        assertBoundedStorageUnavailable(store, transactions, Duration.ofSeconds(1));
        verifyNoInteractions(store);
    }

    @Test
    void runtimeDataSourceShutdownFailsOnceWithinBound() {
        HikariDataSource dataSource = dataSource("runtime-shutdown");
        ReviewM0IdempotencyStore store = mock(ReviewM0IdempotencyStore.class);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        try (Connection ignored = dataSource.getConnection()) {
            assertThat(ignored.isValid(1)).isTrue();
        } catch (SQLException error) {
            throw new AssertionError(error);
        }
        dataSource.close();

        assertBoundedStorageUnavailable(store, transactions, Duration.ofSeconds(1));
        verifyNoInteractions(store);
    }

    @Test
    void exhaustedConnectionPoolTimesOutWithoutRetryOrConnectionLeak() throws Exception {
        try (HikariDataSource dataSource = dataSource("pool-exhaustion");
             Connection held = dataSource.getConnection()) {
            ReviewM0IdempotencyStore store = mock(ReviewM0IdempotencyStore.class);
            DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);

            assertBoundedStorageUnavailable(store, transactions, Duration.ofSeconds(2));
            verifyNoInteractions(store);
            assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            assertThat(dataSource.getHikariPoolMXBean().getTotalConnections()).isEqualTo(1);
        }
    }

    private static void assertBoundedStorageUnavailable(
            ReviewM0IdempotencyStore store,
            DataSourceTransactionManager transactions,
            Duration maximumElapsed
    ) {
        ReviewM0ApiIdempotency wrapper = new ReviewM0ApiIdempotency(store, transactions);
        long started = System.nanoTime();
        assertThatThrownBy(() -> wrapper.execute(
                new IdempotencyScope("owner-a", "POST", "/api/review-m0/cases", "bounded-key"),
                DIGEST, 201, "CASE", String.class,
                () -> new ReviewM0ApiIdempotency.Resource<>("case-a", "must-not-run")))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(elapsed).isLessThan(maximumElapsed);
    }

    private static HikariDataSource dataSource(String name) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("review-m0-" + name);
        config.setJdbcUrl("jdbc:h2:mem:review_m0_" + name.replace('-', '_') + ";MODE=MySQL");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(250);
        return new HikariDataSource(config);
    }

    private static final class AlwaysUnavailableDataSource extends AbstractDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLTransientConnectionException("synthetic", "08001");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }
}
