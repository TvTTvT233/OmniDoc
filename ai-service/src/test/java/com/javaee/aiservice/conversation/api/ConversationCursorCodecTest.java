package com.javaee.aiservice.conversation.api;

import com.javaee.aiservice.conversation.model.ConversationCursor;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationCursorCodecTest {

    private final ConversationCursorCodec codec =
            new ConversationCursorCodec("test-only-cursor-key-with-at-least-32-bytes");

    @Test
    void roundTripsStableSeekBoundary() {
        ConversationCursor boundary = new ConversationCursor(
                Instant.parse("2026-08-11T01:02:03.456Z"),
                "7db98e36-66cd-4be0-8375-b5cd653974a5");

        String encoded = codec.encode(boundary);

        assertThat(codec.decode(encoded)).isEqualTo(boundary);
        assertThat(encoded).doesNotContain(boundary.conversationId());
    }

    @Test
    void rejectsTamperingAndTokensSignedByAnotherKey() {
        String encoded = codec.encode(new ConversationCursor(
                Instant.parse("2026-08-11T01:02:03.456Z"), "conv-1"));
        String tampered = (encoded.charAt(0) == 'A' ? "B" : "A") + encoded.substring(1);
        ConversationCursorCodec anotherCodec = new ConversationCursorCodec(
                "different-test-cursor-key-with-at-least-32-bytes");

        assertInvalidCursor(() -> codec.decode(tampered));
        assertInvalidCursor(() -> anotherCodec.decode(encoded));
    }

    @Test
    void rejectsMalformedOrUnsupportedCursor() {
        assertInvalidCursor(() -> codec.decode(""));
        assertInvalidCursor(() -> codec.decode("plain-base64-is-not-a-signed-cursor"));
        assertInvalidCursor(() -> codec.decode("payload.signature.extra"));
    }

    @Test
    void blankConfigurationUsesAStableRestartSafeLocalKey() {
        ConversationCursor boundary = new ConversationCursor(
                Instant.parse("2026-08-11T01:02:03.456Z"), "conv-local");
        String cursor = new ConversationCursorCodec("").encode(boundary);

        assertThat(new ConversationCursorCodec("").decode(cursor)).isEqualTo(boundary);
    }

    private static void assertInvalidCursor(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ConversationMemoryException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ConversationErrorCode.INVALID_CONVERSATION_CURSOR));
    }
}
