package com.javaee.aiservice.rag.vector.routing;

@FunctionalInterface
public interface ShadowMetricsRecorder {

    void record(ShadowObservation observation);
}
