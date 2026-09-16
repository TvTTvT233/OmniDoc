package com.javaee.aiservice.conversation.service;

import com.javaee.aiservice.conversation.config.MemoryPersistenceProperties;
import com.javaee.aiservice.conversation.model.Conversation;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.model.ConversationStatus;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.conversation.store.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LegacyConversationImporter {

    private static final Logger log = LoggerFactory.getLogger(LegacyConversationImporter.class);
    private static final String USER_PREFIX = "User: ";
    private static final String ASSISTANT_PREFIX = "Assistant: ";

    private final ConversationStore conversationStore;
    private final LegacyConversationSource legacySource;
    private final MemoryPersistenceProperties properties;
    private final AtomicLong importedConversations = new AtomicLong();

    public LegacyConversationImporter(
            ConversationStore conversationStore,
            LegacyConversationSource legacySource,
            MemoryPersistenceProperties properties
    ) {
        this.conversationStore = conversationStore;
        this.legacySource = legacySource;
        this.properties = properties;
    }

    public Optional<Conversation> importIfPresent(String conversationId, String userId) {
        if (!properties.getLegacy().getImport().isEnabled()) {
            return Optional.empty();
        }
        Optional<LegacyConversationSnapshot> snapshot = legacySource.findOwned(conversationId, userId);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }

        Instant importedAt = Instant.now();
        Conversation conversation = new Conversation(
                conversationId,
                userId,
                "Imported conversation",
                ConversationStatus.ACTIVE,
                1,
                importedAt,
                importedAt,
                null,
                0);
        String turnId = UUID.nameUUIDFromBytes(
                ("legacy-turn:" + conversationId).getBytes(StandardCharsets.UTF_8)).toString();
        String clientRequestId = "legacy-import:" + conversationId;
        List<MessageAppendCommand> commands = new ArrayList<>();
        List<String> messages = snapshot.get().messages();
        for (int ordinal = 0; ordinal < messages.size(); ordinal++) {
            commands.add(parse(messages.get(ordinal), turnId, clientRequestId, ordinal));
        }

        Conversation imported;
        try {
            imported = conversationStore.importLegacyConversation(conversation, commands);
        } catch (ConversationMemoryException error) {
            if (error.getCode() != ConversationErrorCode.CONVERSATION_CONFLICT) {
                throw error;
            }
            imported = conversationStore.findOwned(conversationId, userId)
                    .orElseThrow(() -> new ConversationMemoryException(
                            ConversationErrorCode.CONVERSATION_NOT_FOUND,
                            "Conversation was not found"));
        }
        long total = importedConversations.incrementAndGet();
        log.info("Imported legacy conversationId={}, messageCount={}, importedTotal={}",
                conversationId, messages.size(), total);
        return Optional.of(imported);
    }

    public long importedConversationCount() {
        return importedConversations.get();
    }

    public void deleteOwnedSource(String conversationId, String userId) {
        if (!properties.getLegacy().getImport().isEnabled()) {
            return;
        }
        legacySource.deleteOwned(conversationId, userId);
    }

    private static MessageAppendCommand parse(
            String raw,
            String turnId,
            String clientRequestId,
            int ordinal
    ) {
        Map<String, Object> metadata = Map.of("legacyImported", true);
        if (raw.startsWith(USER_PREFIX)) {
            return new MessageAppendCommand(
                    null, turnId, clientRequestId, ordinal, ConversationRole.USER,
                    ConversationMessageType.TEXT, raw.substring(USER_PREFIX.length()),
                    null, null, metadata);
        }
        if (raw.startsWith(ASSISTANT_PREFIX)) {
            return new MessageAppendCommand(
                    null, turnId, clientRequestId, ordinal, ConversationRole.ASSISTANT,
                    ConversationMessageType.TEXT, raw.substring(ASSISTANT_PREFIX.length()),
                    null, null, metadata);
        }
        return new MessageAppendCommand(
                null, turnId, clientRequestId, ordinal, ConversationRole.SYSTEM,
                ConversationMessageType.EVENT, raw, null, null,
                Map.of("legacyImported", true, "legacyUnparsed", true, "displayable", true));
    }
}
