package com.javaee.aiservice.aiops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiOpsSchedulerTest {

    @Test
    void schedulingIsOptInAndDoesNotMatchWhenPropertyIsMissing() {
        ConditionalOnProperty condition = AiOpsScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("ai.aiops.scheduling.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void redisFailureCanaryIsContainedCountedAndLoggedWithoutThrowable() {
        MonitoringService monitoring = mock(MonitoringService.class);
        FaultDetector detector = mock(FaultDetector.class);
        String marker = "redis://user:secret@runtime-marker:6379/body";
        doThrow(new RedisConnectionFailureException(marker))
                .when(monitoring).scheduledPersistMetrics();
        AiOpsScheduler scheduler = new AiOpsScheduler(monitoring, detector);
        CapturedLog captured = captureSchedulerLog();

        scheduler.persistMetricsSafely();

        assertThat(scheduler.persistenceFailureCount()).isEqualTo(1);
        verify(monitoring).incrementCounter("aiops.metrics.persistence.failures");
        assertThat(captured.appender.list).hasSize(1);
        ILoggingEvent event = captured.appender.list.get(0);
        assertThat(event.getFormattedMessage())
                .contains("event=AIOPS_METRICS_PERSIST_FAILED")
                .contains("reasonCode=AIOPS_REDIS_UNAVAILABLE")
                .contains("exceptionCategory=REDIS")
                .contains("retryable=true retryCount=1 count=1")
                .doesNotContain(marker, "redis://", "secret", "body");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void detectionFailureCanaryDoesNotEscapeScheduler() {
        MonitoringService monitoring = mock(MonitoringService.class);
        FaultDetector detector = mock(FaultDetector.class);
        doThrow(new IllegalStateException("sensitive-body-marker"))
                .when(detector).scheduledDetection();
        AiOpsScheduler scheduler = new AiOpsScheduler(monitoring, detector);
        CapturedLog captured = captureSchedulerLog();

        scheduler.detectFaultsSafely();

        assertThat(scheduler.detectionFailureCount()).isEqualTo(1);
        verify(monitoring).incrementCounter("aiops.detection.failures");
        assertThat(captured.appender.list).hasSize(1);
        assertThat(captured.appender.list.get(0).getFormattedMessage())
                .contains("event=AIOPS_DETECTION_FAILED")
                .contains("reasonCode=AIOPS_DETECTION_DEPENDENCY_UNAVAILABLE")
                .doesNotContain("sensitive-body-marker");
        assertThat(captured.appender.list.get(0).getThrowableProxy()).isNull();
    }

    private static CapturedLog captureSchedulerLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(AiOpsScheduler.class);
        logger.detachAndStopAllAppenders();
        logger.setLevel(Level.ALL);
        logger.setAdditive(false);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLog(logger, appender);
    }

    private record CapturedLog(Logger logger, ListAppender<ILoggingEvent> appender) {
    }
}
