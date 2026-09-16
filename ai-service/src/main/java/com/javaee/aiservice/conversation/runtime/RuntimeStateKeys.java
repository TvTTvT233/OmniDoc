package com.javaee.aiservice.conversation.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class RuntimeStateKeys {

    private static final String PREFIX = "ai:runtime:conversation:";
    private static final String LEGACY_PREFIX = "ctx:";

    private RuntimeStateKeys() {
    }

    static String fieldKey(RuntimeStateScope scope, RuntimeStateField field) {
        return fieldKey(scope.conversationId(), scope.traceId(), field);
    }

    static String fieldKey(String conversationId, String traceId, RuntimeStateField field) {
        return conversationPrefix(conversationId)
                + ":trace:" + encode(traceId)
                + ":field:" + field.wireName();
    }

    static String traceIndexKey(String conversationId) {
        return conversationPrefix(conversationId) + ":trace-index";
    }

    static String legacyContextKey(String conversationId) {
        return LEGACY_PREFIX + conversationId;
    }

    private static String conversationPrefix(String conversationId) {
        return PREFIX + encode(conversationId);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
