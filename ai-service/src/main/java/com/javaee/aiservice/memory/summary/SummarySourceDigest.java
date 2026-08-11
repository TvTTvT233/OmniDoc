package com.javaee.aiservice.memory.summary;

import com.javaee.aiservice.conversation.model.ConversationMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class SummarySourceDigest {

    private SummarySourceDigest() {
    }

    public static String compute(
            String conversationId,
            long fromSequence,
            long toSequence,
            List<ConversationMessage> sourceMessages
    ) {
        List<ConversationMessage> ordered = sourceMessages.stream()
                .sorted(Comparator.comparingLong(ConversationMessage::sequence))
                .toList();
        requireContinuous(fromSequence, toSequence, ordered);
        StringBuilder canonical = new StringBuilder()
                .append(conversationId).append('\n')
                .append(fromSequence).append('\n')
                .append(toSequence).append('\n');
        for (ConversationMessage message : ordered) {
            canonical.append(message.sequence()).append('|')
                    .append(message.messageId()).append('|')
                    .append(message.role()).append('|')
                    .append(message.messageType()).append('|')
                    .append(message.contentHash()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public static void requireContinuous(
            long fromSequence,
            long toSequence,
            List<ConversationMessage> ordered
    ) {
        if (fromSequence < 1 || toSequence < fromSequence
                || ordered.size() != toSequence - fromSequence + 1) {
            throw new SummaryValidationException("Summary source coverage is not continuous");
        }
        long expected = fromSequence;
        for (ConversationMessage message : ordered) {
            if (message.sequence() != expected) {
                throw new SummaryValidationException("Summary source coverage contains a sequence gap");
            }
            expected++;
        }
    }
}
