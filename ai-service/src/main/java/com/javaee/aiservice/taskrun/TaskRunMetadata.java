package com.javaee.aiservice.taskrun;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal JSON-safe metadata only. This is a bounded observation history, not a journal. */
public final class TaskRunMetadata {
    public static final String KEY = "_taskRunV1";
    public static final int LIMIT = 256;
    private TaskRunMetadata() {}

    public static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) raw.forEach((k, v) -> { if (k instanceof String s) result.put(s, v); });
        return result;
    }
    public static List<Map<String, Object>> rows(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) list.forEach(v -> { if (v instanceof Map<?, ?>) result.add(map(v)); });
        return result;
    }
    public static String text(Object value) { return value instanceof String s ? s : null; }
    public static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }
    public static Integer integer(Object value) { return value instanceof Number n ? n.intValue() : null; }
    public static Instant instant(Object value) {
        Long millis = number(value);
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }
    public static String status(String raw) {
        if (raw == null) return "UNKNOWN";
        return switch (raw) {
            case "queued", "pending" -> "QUEUED";
            case "running" -> "RUNNING";
            case "action_required", "waiting_user", "planned" -> "WAITING_INPUT";
            case "success" -> "SUCCEEDED";
            case "error", "failed", "blocked" -> "FAILED";
            case "cancelled" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }
    public static void event(Map<String, Object> metadata, String type, String stepId, String callId, String status) {
        List<Map<String, Object>> events = rows(metadata.get("events"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("stepId", stepId);
        event.put("toolCallId", callId);
        event.put("status", status);
        event.put("occurredAt", System.currentTimeMillis());
        events.add(event);
        if (events.size() > LIMIT) { events.remove(0); metadata.put("historyTruncated", true); }
        metadata.put("events", events);
        metadata.put("revision", number(metadata.get("revision")) == null ? 1L : number(metadata.get("revision")) + 1);
    }
    public static List<TaskRunView.Event> events(Map<String, Object> metadata) {
        return rows(metadata.get("events")).stream().map(e -> new TaskRunView.Event(
                text(e.get("eventId")), text(e.get("type")), text(e.get("stepId")),
                text(e.get("toolCallId")), text(e.get("status")), instant(e.get("occurredAt")))).toList();
    }
}
