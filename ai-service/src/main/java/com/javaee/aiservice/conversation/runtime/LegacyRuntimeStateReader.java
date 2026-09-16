package com.javaee.aiservice.conversation.runtime;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded compatibility reader for pre-M03 {@code ctx:*} hashes.
 *
 * <p>Legacy hashes have no trace identity, so they are never imported into the new trace-scoped
 * store or any Summary. Only scalar document/selection/page fields are exposed, and every read
 * applies a short compatibility TTL. Approval/tool Maps and credential-like fields are ignored.</p>
 */
@Component
public class LegacyRuntimeStateReader {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RuntimeStateProperties properties;

    public LegacyRuntimeStateReader(
            RedisTemplate<String, Object> redisTemplate,
            RuntimeStateProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public LegacyReadResult readOptional(String conversationId) {
        String controlledConversationId = RuntimeStateScope.requireConversationId(conversationId);
        String key = RuntimeStateKeys.legacyContextKey(controlledConversationId);
        try {
            HashOperations<String, Object, Object> hashes = redisTemplate.opsForHash();
            Map<Object, Object> stored = hashes.entries(key);
            Map<String, String> controlled = new LinkedHashMap<>();
            if (stored != null) {
                copyText(stored, controlled, "currentDocumentId", "currentDocumentId");
                copyText(stored, controlled, "documentId", "currentDocumentId");
                copyText(stored, controlled, "selectedText", "selectedText");
                copyText(stored, controlled, "pageContext", "pageContext");
            }
            redisTemplate.expire(key, properties.getLegacyCompatibilityTtl());
            return LegacyReadResult.available(controlled);
        } catch (RuntimeException e) {
            return LegacyReadResult.degraded();
        }
    }

    private void copyText(
            Map<Object, Object> stored,
            Map<String, String> controlled,
            String legacyKey,
            String targetKey
    ) {
        if (controlled.containsKey(targetKey)) {
            return;
        }
        Object value = stored.get(legacyKey);
        if (!(value instanceof String text) || text.isBlank()) {
            return;
        }
        RuntimeStateField field = switch (targetKey) {
            case "currentDocumentId" -> RuntimeStateField.CURRENT_DOCUMENT_ID;
            case "selectedText" -> RuntimeStateField.SELECTED_TEXT;
            case "pageContext" -> RuntimeStateField.PAGE_CONTEXT;
            default -> throw new IllegalArgumentException("Unsupported legacy Runtime State field");
        };
        int maxBytes = properties.effectivePolicy(field).maxBytes();
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            controlled.put(targetKey, text);
        }
    }

    public record LegacyReadResult(Availability availability, Map<String, String> values) {

        public LegacyReadResult {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public static LegacyReadResult available(Map<String, String> values) {
            return new LegacyReadResult(Availability.AVAILABLE, values);
        }

        public static LegacyReadResult degraded() {
            return new LegacyReadResult(Availability.DEGRADED_UNAVAILABLE, Map.of());
        }
    }

    public enum Availability {
        AVAILABLE,
        DEGRADED_UNAVAILABLE
    }
}
