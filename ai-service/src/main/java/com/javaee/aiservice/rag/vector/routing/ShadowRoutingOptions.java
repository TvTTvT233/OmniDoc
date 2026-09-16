package com.javaee.aiservice.rag.vector.routing;

/**
 * Runtime controls for shadow traffic. The timeout is independent from the baseline request.
 */
public record ShadowRoutingOptions(double sampleRate, long timeoutMillis) {

    public static final double DEFAULT_SAMPLE_RATE = 1.0d;
    public static final long DEFAULT_TIMEOUT_MILLIS = 3_000L;

    public ShadowRoutingOptions {
        if (!Double.isFinite(sampleRate) || sampleRate < 0.0d || sampleRate > 1.0d) {
            throw new IllegalArgumentException("sampleRate must be between 0 and 1");
        }
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
    }

    public static ShadowRoutingOptions defaults() {
        return new ShadowRoutingOptions(DEFAULT_SAMPLE_RATE, DEFAULT_TIMEOUT_MILLIS);
    }
}
