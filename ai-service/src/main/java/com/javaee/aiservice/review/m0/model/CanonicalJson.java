package com.javaee.aiservice.review.m0.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class CanonicalJson {
    private static final ObjectMapper STRING_ENCODER = new ObjectMapper();
    private static final Comparator<String> CODE_POINT_ORDER = UnicodeCodePointComparator.INSTANCE;

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    public static String sha256(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(write(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            output.append(quoted(text));
        } else if (value instanceof Enum<?> enumeration) {
            output.append(quoted(enumeration.name()));
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof Number number) {
            appendNumber(output, number);
        } else if (value instanceof Map<?, ?> map) {
            appendMap(output, map);
        } else if (value instanceof Iterable<?> iterable) {
            appendIterable(output, iterable);
        } else if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                items.add(Array.get(value, index));
            }
            appendIterable(output, items);
        } else {
            throw ModelChecks.invalid("Unsupported canonical JSON value: " + value.getClass().getName());
        }
    }

    private static void appendMap(StringBuilder output, Map<?, ?> map) {
        List<String> keys = map.keySet().stream().map(key -> {
            if (!(key instanceof String text)) {
                throw ModelChecks.invalid("Canonical JSON object keys must be strings");
            }
            return text;
        }).sorted(CODE_POINT_ORDER).toList();
        output.append('{');
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                output.append(',');
            }
            first = false;
            output.append(quoted(key)).append(':');
            append(output, map.get(key));
        }
        output.append('}');
    }

    private static void appendIterable(StringBuilder output, Iterable<?> iterable) {
        output.append('[');
        boolean first = true;
        for (Object value : iterable) {
            if (!first) {
                output.append(',');
            }
            first = false;
            append(output, value);
        }
        output.append(']');
    }

    private static void appendNumber(StringBuilder output, Number number) {
        if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || number instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw ModelChecks.invalid("Canonical JSON does not allow non-finite numbers");
        }
        output.append(new BigDecimal(number.toString()).stripTrailingZeros().toPlainString());
    }

    private static String quoted(String value) {
        try {
            return STRING_ENCODER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw ModelChecks.invalid("String cannot be encoded as JSON");
        }
    }
}
