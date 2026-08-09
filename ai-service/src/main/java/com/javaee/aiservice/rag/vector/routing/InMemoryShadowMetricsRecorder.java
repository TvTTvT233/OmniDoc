package com.javaee.aiservice.rag.vector.routing;

import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded in-memory aggregates plus a sanitized structured log for each shadow observation.
 */
public final class InMemoryShadowMetricsRecorder implements ShadowMetricsRecorder {

    private static final Logger log = LoggerFactory.getLogger(InMemoryShadowMetricsRecorder.class);

    private final LongAdder samples = new LongAdder();
    private final LongAdder successfulComparisons = new LongAdder();
    private final LongAdder operations = new LongAdder();
    private final DoubleAdder overlap = new DoubleAdder();
    private final LongAdder baselineNanos = new LongAdder();
    private final LongAdder qdrantNanos = new LongAdder();
    private final Map<VectorErrorCode, LongAdder> errors = new ConcurrentHashMap<>();

    @Override
    public void record(ShadowObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }
        operations.increment();
        baselineNanos.add(observation.baselineDurationNanos());
        qdrantNanos.add(observation.qdrantDurationNanos());
        if (observation.operation() == ShadowOperation.SEARCH) {
            samples.increment();
        }
        if (observation.successfulComparison()) {
            successfulComparisons.increment();
            overlap.add(observation.overlapAtK());
        }
        if (observation.errorCode() != null) {
            errors.computeIfAbsent(observation.errorCode(), ignored -> new LongAdder()).increment();
        }

        log.info(
                "Vector shadow observation operation={} topK={} overlapAtK={} baselineDurationMs={} "
                        + "qdrantDurationMs={} errorCode={} logicalIds={} baselineIds={} qdrantIds={}",
                observation.operation(),
                observation.requestedTopK(),
                observation.overlapAtK(),
                nanosToMillis(observation.baselineDurationNanos()),
                nanosToMillis(observation.qdrantDurationNanos()),
                observation.errorCode(),
                observation.logicalIds(),
                observation.baselineIds(),
                observation.qdrantIds()
        );
    }

    public ShadowMetricsSnapshot snapshot() {
        long operationCount = operations.sum();
        long comparisonCount = successfulComparisons.sum();
        EnumMap<VectorErrorCode, Long> errorSnapshot = new EnumMap<>(VectorErrorCode.class);
        errors.forEach((code, count) -> errorSnapshot.put(code, count.sum()));
        return new ShadowMetricsSnapshot(
                samples.sum(),
                comparisonCount,
                operationCount,
                comparisonCount == 0L ? 0.0d : overlap.sum() / comparisonCount,
                operationCount == 0L ? 0.0d : nanosToMillis(baselineNanos.sum()) / operationCount,
                operationCount == 0L ? 0.0d : nanosToMillis(qdrantNanos.sum()) / operationCount,
                errorSnapshot
        );
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }
}
