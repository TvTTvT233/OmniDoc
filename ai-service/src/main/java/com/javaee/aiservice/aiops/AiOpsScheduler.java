package com.javaee.aiservice.aiops;

import com.javaee.aiservice.logging.SafeDiagnosticLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Opt-in persistence/detection scheduling; interactive in-memory metrics remain available. */
@Component
@ConditionalOnProperty(name = "ai.aiops.scheduling.enabled", havingValue = "true")
public final class AiOpsScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiOpsScheduler.class);

    private final MonitoringService monitoringService;
    private final FaultDetector faultDetector;
    private final AtomicLong persistenceFailures = new AtomicLong();
    private final AtomicLong detectionFailures = new AtomicLong();

    public AiOpsScheduler(MonitoringService monitoringService, FaultDetector faultDetector) {
        this.monitoringService = monitoringService;
        this.faultDetector = faultDetector;
    }

    @Scheduled(fixedDelayString = "${ai.aiops.metrics.persist-interval-ms:60000}")
    public void persistMetricsSafely() {
        try {
            monitoringService.scheduledPersistMetrics();
        } catch (RuntimeException failure) {
            long count = persistenceFailures.incrementAndGet();
            monitoringService.incrementCounter("aiops.metrics.persistence.failures");
            SafeDiagnosticLogger.warn(
                    log,
                    "AIOPS_METRICS_PERSIST_FAILED",
                    "AIOPS_REDIS_UNAVAILABLE",
                    failure,
                    true,
                    count,
                    count);
        }
    }

    @Scheduled(fixedDelayString = "${ai.aiops.detection.interval-ms:60000}")
    public void detectFaultsSafely() {
        try {
            faultDetector.scheduledDetection();
        } catch (RuntimeException failure) {
            long count = detectionFailures.incrementAndGet();
            monitoringService.incrementCounter("aiops.detection.failures");
            SafeDiagnosticLogger.warn(
                    log,
                    "AIOPS_DETECTION_FAILED",
                    "AIOPS_DETECTION_DEPENDENCY_UNAVAILABLE",
                    failure,
                    true,
                    count,
                    count);
        }
    }

    long persistenceFailureCount() {
        return persistenceFailures.get();
    }

    long detectionFailureCount() {
        return detectionFailures.get();
    }
}
