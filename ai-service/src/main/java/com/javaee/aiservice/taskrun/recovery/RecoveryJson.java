package com.javaee.aiservice.taskrun.recovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Canonical, bounded internal state. Transport credentials never belong to a checkpoint. */
public final class RecoveryJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PRIVATE = Set.of("authorization", "accesstoken", "refreshtoken",
            "approvaltoken", "agentapprovaltoken", "password", "apikey", "cookie", "confirmedaction", "requireconfirmation");
    private RecoveryJson() {}
    public static Object clean(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (!PRIVATE.contains(key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT)))
                    out.put(key, clean(v));
            });
            return out;
        }
        if (value instanceof Collection<?> list) return list.stream().map(RecoveryJson::clean).toList();
        return value;
    }
    public static String json(Object value) {
        try {
            Object plain = MAPPER.convertValue(value, Object.class);
            String json = MAPPER.writeValueAsString(clean(plain));
            if (json.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024)
                throw new IllegalArgumentException("TASK_STATE_TOO_LARGE");
            return json;
        } catch (java.io.IOException e) { throw new IllegalArgumentException("INVALID_TASK_STATE", e); }
    }
    public static Map<String, Object> map(String json) {
        try { return MAPPER.readValue(json, new TypeReference<>() {}); }
        catch (java.io.IOException e) { throw new IllegalStateException("INVALID_STORED_TASK_STATE", e); }
    }
    public static String digest(Object value) { return hash(json(value)); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
