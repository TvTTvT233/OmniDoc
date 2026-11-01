package com.javaee.aiservice.taskrun;

import java.time.Instant;
import java.util.List;

/** Public, content-free projection of an existing task source. */
public record TaskRunView(int protocolVersion, String runId, String source, String sourceId,
        String kind, String status, String sourceStatus, String businessStatus,
        String waitingReason, boolean cancellationRequested, Integer attempt,
        Long revision, Instant createdAt, Instant startedAt, Instant finishedAt,
        Instant observedAt, List<Link> links, List<Step> steps,
        List<ToolCall> toolCalls, Result result, List<Event> events, Capabilities capabilities) {
    public TaskRunView {
        links = links == null ? List.of() : List.copyOf(links);
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        events = events == null ? List.of() : List.copyOf(events);
    }
    public record Link(String relation, String runId) {}
    public record Step(String stepId, String kind, String status, String sourceStatus,
            Integer attempt, Instant startedAt, Instant finishedAt) {}
    public record ToolCall(String toolCallId, String stepId, String toolName, String status,
            Integer attempt, Instant startedAt, Instant finishedAt) {}
    public record Result(String kind, String resourceId, String outcome, String freshness) {}
    public record Event(String eventId, String type, String stepId, String toolCallId,
            String status, Instant occurredAt) {}
    public record Capabilities(String persistence, String history, boolean recoverySupported,
            List<String> limitations) {
        public Capabilities { limitations = limitations == null ? List.of() : List.copyOf(limitations); }
    }
}
