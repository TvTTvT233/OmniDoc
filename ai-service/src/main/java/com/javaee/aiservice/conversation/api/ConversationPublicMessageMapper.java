package com.javaee.aiservice.conversation.api;

import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.ConversationRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** A deny-by-default projection from durable internal messages to the public UI contract. */
@Component
public final class ConversationPublicMessageMapper {

    private static final Set<String> PUBLIC_METADATA_KEYS =
            Set.of("displayable", "eventType", "label", "status", "toolName");
    private static final Pattern PUBLIC_IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final int MAX_PUBLIC_METADATA_TEXT = 256;

    public Optional<PublicConversationMessageResponse> toPublic(ConversationMessage message) {
        if (message == null) {
            return Optional.empty();
        }
        if ((message.role() == ConversationRole.USER || message.role() == ConversationRole.ASSISTANT)
                && message.messageType() == ConversationMessageType.TEXT) {
            return Optional.of(response(message, message.content(), Map.of()));
        }
        if (message.role() != ConversationRole.TOOL
                || message.messageType() != ConversationMessageType.EVENT
                || !Boolean.TRUE.equals(message.metadata().get("displayable"))) {
            return Optional.empty();
        }

        Map<String, Object> metadata = publicMetadata(message.metadata());
        return Optional.of(response(message, publicToolContent(metadata), metadata));
    }

    private static PublicConversationMessageResponse response(
            ConversationMessage message,
            String content,
            Map<String, Object> metadata
    ) {
        return new PublicConversationMessageResponse(
                message.messageId(),
                message.conversationId(),
                message.sequence(),
                message.role().name(),
                message.messageType().name(),
                content,
                publicIdentifier(message.traceId()),
                publicIdentifier(message.toolCallId()),
                metadata,
                message.createdAt().toString());
    }

    private static Map<String, Object> publicMetadata(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : PUBLIC_METADATA_KEYS) {
            Object value = source.get(key);
            if (value instanceof Boolean booleanValue) {
                result.put(key, booleanValue);
            } else if (value instanceof String text) {
                result.put(key, sanitizeMetadataText(text));
            }
        }
        result.put("displayable", true);
        return result;
    }

    private static String publicToolContent(Map<String, Object> metadata) {
        String label = text(metadata.get("label"));
        if (!label.isBlank()) {
            return label;
        }
        String toolName = text(metadata.get("toolName"));
        String status = text(metadata.get("status"));
        if (!toolName.isBlank() && !status.isBlank()) {
            return toolName + " · " + status;
        }
        if (!toolName.isBlank()) {
            return toolName;
        }
        return "Tool event";
    }

    private static String publicIdentifier(String value) {
        return value != null && PUBLIC_IDENTIFIER.matcher(value).matches() ? value : null;
    }

    private static String sanitizeMetadataText(String value) {
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), MAX_PUBLIC_METADATA_TEXT));
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(MAX_PUBLIC_METADATA_TEXT)
                .forEach(sanitized::appendCodePoint);
        return sanitized.toString();
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }
}
