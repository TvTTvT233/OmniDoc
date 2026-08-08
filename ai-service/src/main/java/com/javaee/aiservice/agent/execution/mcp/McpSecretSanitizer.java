package com.javaee.aiservice.agent.execution.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redacts configured credentials from values that can cross a log or contract boundary.
 */
final class McpSecretSanitizer {

    private static final String REDACTED = "***";
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|token|secret)=([^&\\s]+)"
    );

    private final List<String> secrets;

    McpSecretSanitizer(Collection<String> secrets) {
        this.secrets = secrets == null ? List.of() : secrets.stream()
                .filter(secret -> secret != null && !secret.isBlank())
                .map(String::trim)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String sanitized = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        sanitized = QUERY_SECRET.matcher(sanitized).replaceAll("$1=" + REDACTED);
        for (String secret : secrets) {
            sanitized = sanitized.replace(secret, REDACTED);
        }
        return sanitized;
    }

    Map<String, Object> sanitizeMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(sanitize(key), sanitizeValue(value)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value) {
        if (value instanceof String text) {
            return sanitize(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(sanitize(String.valueOf(key)), sanitizeValue(item)));
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(sanitizeValue(item)));
            return copy;
        }
        if (value instanceof Object[] values) {
            List<Object> copy = new ArrayList<>(values.length);
            for (Object item : values) {
                copy.add(sanitizeValue(item));
            }
            return copy;
        }
        return value;
    }
}
