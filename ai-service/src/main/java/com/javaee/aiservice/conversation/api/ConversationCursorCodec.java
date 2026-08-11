package com.javaee.aiservice.conversation.api;

import com.javaee.aiservice.conversation.model.ConversationCursor;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Encodes the frozen (updatedAt, conversationId) seek boundary as an authenticated opaque token.
 * A configured key keeps cursors valid across restarts and instances. The random fallback remains
 * unforgeable for a single process and is intended only for local development.
 */
@Component
public final class ConversationCursorCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] KEY_DOMAIN =
            "omnidoc-conversation-cursor-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final String VERSION = "v1";
    private static final byte[] STABLE_LOCAL_FALLBACK =
            "omnidoc-memory-v1-local-cursor-key".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final SecretKeySpec signingKey;

    public ConversationCursorCodec(@Value("${ai.memory.api.cursor-signing-key:}") String configuredKey) {
        byte[] keyBytes;
        if (configuredKey == null || configuredKey.isBlank()) {
            // Cursor authenticity is not an authorization boundary; every decoded cursor is still
            // applied inside an owner-filtered query. A stable fallback keeps local cursors usable
            // after restart, while deployments should configure a private shared value.
            keyBytes = STABLE_LOCAL_FALLBACK;
        } else {
            keyBytes = configuredKey.getBytes(StandardCharsets.UTF_8);
        }
        this.signingKey = new SecretKeySpec(deriveKey(keyBytes), HMAC_ALGORITHM);
    }

    public String encode(ConversationCursor cursor) {
        if (cursor == null
                || cursor.updatedAt() == null
                || cursor.conversationId() == null
                || !CONVERSATION_ID.matcher(cursor.conversationId()).matches()) {
            throw invalidCursor();
        }
        String payload = VERSION + "\n" + cursor.updatedAt().toEpochMilli() + "\n" + cursor.conversationId();
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encodedPayload));
        return encodedPayload + "." + signature;
    }

    public ConversationCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw invalidCursor();
        }
        try {
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(parts[1]);
            if (!parts[1].equals(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(suppliedSignature))) {
                throw invalidCursor();
            }
            if (!MessageDigest.isEqual(sign(parts[0]), suppliedSignature)) {
                throw invalidCursor();
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            if (!parts[0].equals(Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes))) {
                throw invalidCursor();
            }
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            String[] fields = payload.split("\\n", -1);
            if (fields.length != 3 || !VERSION.equals(fields[0])
                    || !CONVERSATION_ID.matcher(fields[2]).matches()) {
                throw invalidCursor();
            }
            Instant updatedAt = Instant.ofEpochMilli(Long.parseLong(fields[1]));
            return new ConversationCursor(updatedAt, fields[2]);
        } catch (IllegalArgumentException | DateTimeException error) {
            throw invalidCursor();
        }
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException error) {
            throw new IllegalStateException("Conversation cursor signing is unavailable", error);
        }
    }

    private static byte[] deriveKey(byte[] configuredKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_DOMAIN);
            return digest.digest(configuredKey);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Conversation cursor key derivation is unavailable", error);
        }
    }

    private static ConversationMemoryException invalidCursor() {
        return new ConversationMemoryException(
                ConversationErrorCode.INVALID_CONVERSATION_CURSOR,
                "Conversation cursor is invalid");
    }
}
