package com.javaee.aiservice.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SafeDiagnosticLoggerTest {

    @Test
    void emitsOnlyBoundedFieldsWithoutThrowableMessageOrStack() {
        CapturedLog captured = capture();
        String marker = "jdbc:mysql://runtime-marker:3306/db?password=marker objectKey=marker.jwt.body";

        SafeDiagnosticLogger.error(
                captured.logger,
                "REVIEW_M0_STORAGE_FAILED",
                "REVIEW_M0_STORAGE_UNAVAILABLE",
                new DataAccessResourceFailureException(marker),
                true,
                2,
                3,
                "req_safe-01",
                "corr_safe-01");

        ILoggingEvent event = captured.singleEvent();
        assertThat(event.getFormattedMessage())
                .contains("event=REVIEW_M0_STORAGE_FAILED")
                .contains("reasonCode=REVIEW_M0_STORAGE_UNAVAILABLE")
                .contains("requestId=req_safe-01")
                .contains("correlationId=corr_safe-01")
                .contains("exceptionCategory=DATABASE")
                .contains("retryable=true retryCount=2 count=3")
                .doesNotContain(marker)
                .doesNotContain("jdbc:mysql", "objectKey", "marker.jwt.body");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void classifiesRedisAndRejectsUntrustedDiagnosticTokens() {
        CapturedLog captured = capture();

        SafeDiagnosticLogger.warn(
                captured.logger,
                "unsafe event\nbody",
                "unsafe reason=value",
                new RedisConnectionFailureException("redis://user:secret@runtime-marker:6379"),
                true,
                -1,
                0,
                "req\nbody",
                null);

        ILoggingEvent event = captured.singleEvent();
        assertThat(event.getFormattedMessage())
                .contains("event=UNCLASSIFIED_EVENT")
                .contains("reasonCode=UNCLASSIFIED_FAILURE")
                .contains("requestId=UNAVAILABLE")
                .contains("exceptionCategory=REDIS")
                .contains("retryCount=0 count=1")
                .doesNotContain("secret", "redis://", "body");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void classifiesDatabaseCompetitionWithoutLeakingItsMessage() {
        CapturedLog captured = capture();

        SafeDiagnosticLogger.warn(
                captured.logger,
                "REVIEW_M0_DB_COMPETITION",
                "REVIEW_M0_CONCURRENT_WRITE",
                new ConcurrencyFailureException("duplicate-key body=private"),
                true,
                1,
                2);

        ILoggingEvent event = captured.singleEvent();
        assertThat(event.getFormattedMessage())
                .contains("exceptionCategory=CONCURRENCY")
                .contains("retryable=true retryCount=1 count=2")
                .doesNotContain("duplicate-key", "body=private");
        assertThat(event.getThrowableProxy()).isNull();
    }

    private static CapturedLog capture() {
        Logger logger = (Logger) LoggerFactory.getLogger("safe-diagnostic-test-" + UUID.randomUUID());
        logger.setLevel(Level.ALL);
        logger.setAdditive(false);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLog(logger, appender);
    }

    private record CapturedLog(Logger logger, ListAppender<ILoggingEvent> appender) {
        private ILoggingEvent singleEvent() {
            assertThat(appender.list).hasSize(1);
            return appender.list.get(0);
        }
    }
}
