package com.javaee.aiservice.conversation.service;

import com.javaee.aiservice.conversation.config.MemoryPersistenceProperties;
import com.javaee.aiservice.conversation.model.Conversation;
import com.javaee.aiservice.conversation.model.ConversationCursor;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessagePage;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.ConversationPage;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.model.ConversationStatus;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.conversation.runtime.RuntimeStateCleanup;
import com.javaee.aiservice.conversation.store.ConversationStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationService {

    public static final int MAX_PAGE_SIZE = 200;

    private final ConversationStore conversationStore;
    private final LegacyConversationImporter legacyImporter;
    private final RuntimeStateCleanup runtimeStateCleanup;
    private final MemoryPersistenceProperties properties;

    public ConversationService(
            ConversationStore conversationStore,
            LegacyConversationImporter legacyImporter,
            RuntimeStateCleanup runtimeStateCleanup,
            MemoryPersistenceProperties properties
    ) {
        this.conversationStore = conversationStore;
        this.legacyImporter = legacyImporter;
        this.runtimeStateCleanup = runtimeStateCleanup;
        this.properties = properties;
    }

    public Conversation createConversation(String userId) {
        return createConversation(userId, "New conversation");
    }

    public Conversation createConversation(String userId, String title) {
        requireEnabled();
        requireText(userId, "userId");
        if (userId.length() > 128) {
            throw invalid("userId exceeds the storage contract");
        }
        if (title != null && title.length() > 255) {
            throw invalid("title exceeds the storage contract");
        }
        Instant now = Instant.now();
        Conversation conversation = new Conversation(
                UUID.randomUUID().toString(),
                userId,
                title,
                ConversationStatus.ACTIVE,
                1,
                now,
                now,
                null,
                0);
        return conversationStore.create(conversation);
    }

    public Conversation requireOwned(String conversationId, String userId) {
        requireEnabled();
        requireConversationIdentity(conversationId, userId);
        Optional<Conversation> conversation = conversationStore.findOwned(conversationId, userId);
        if (conversation.isPresent()) {
            return conversation.get();
        }
        return legacyImporter.importIfPresent(conversationId, userId)
                .orElseThrow(ConversationService::notFound);
    }

    public ConversationPage listConversations(
            String userId,
            ConversationStatus status,
            ConversationCursor cursor,
            int limit
    ) {
        requireEnabled();
        requireText(userId, "userId");
        if (userId.length() > 128) {
            throw invalid("userId exceeds the storage contract");
        }
        validateLimit(limit);
        return conversationStore.listOwned(userId, status, cursor, limit);
    }

    public ConversationMessagePage listMessages(
            String conversationId,
            String userId,
            Long beforeSequence,
            int limit
    ) {
        requireOwned(conversationId, userId);
        validateLimit(limit);
        if (beforeSequence != null && beforeSequence <= 0) {
            throw invalid("beforeSequence must be positive");
        }
        return conversationStore.listMessagesOwned(conversationId, userId, beforeSequence, limit);
    }

    public List<ConversationMessage> listMessagesAfter(
            String conversationId,
            String userId,
            long afterSequence,
            int limit
    ) {
        requireOwned(conversationId, userId);
        validateLimit(limit);
        if (afterSequence < 0) {
            throw invalid("afterSequence must not be negative");
        }
        return conversationStore.listMessagesAfterOwned(conversationId, userId, afterSequence, limit);
    }

    public List<ConversationMessage> appendMessages(
            String conversationId,
            String userId,
            List<MessageAppendCommand> commands
    ) {
        requireOwned(conversationId, userId);
        return conversationStore.appendMessages(conversationId, userId, commands);
    }

    public List<ConversationMessage> appendExchange(
            String conversationId,
            String userId,
            String clientRequestId,
            String turnId,
            String userMessage,
            String assistantMessage
    ) {
        List<MessageAppendCommand> commands = List.of(
                MessageAppendCommand.text(turnId, clientRequestId, 0, ConversationRole.USER, userMessage),
                MessageAppendCommand.text(turnId, clientRequestId, 1, ConversationRole.ASSISTANT, assistantMessage));
        return appendMessages(conversationId, userId, commands);
    }

    public Conversation archive(String conversationId, String userId) {
        requireOwned(conversationId, userId);
        return conversationStore.changeStatus(
                conversationId, userId, ConversationStatus.ACTIVE, ConversationStatus.ARCHIVED);
    }

    public Conversation restore(String conversationId, String userId) {
        requireOwned(conversationId, userId);
        return conversationStore.changeStatus(
                conversationId, userId, ConversationStatus.ARCHIVED, ConversationStatus.ACTIVE);
    }

    public void delete(String conversationId, String userId) {
        requireOwned(conversationId, userId);
        // Runtime cleanup is required before the durable delete. If Redis is unavailable, the
        // conversation remains owned and durable so the caller can safely retry deletion.
        runtimeStateCleanup.clearConversation(conversationId);
        // Clear a compatible legacy source before committing the durable delete. Otherwise a
        // subsequent lookup could import the same conversation again and make deletion reversible.
        legacyImporter.deleteOwnedSource(conversationId, userId);
        conversationStore.deleteOwned(conversationId, userId);
    }

    public List<String> legacyHistory(String conversationId, String userId, int limit) {
        ConversationMessagePage page = listMessages(conversationId, userId, null, limit);
        List<String> history = new ArrayList<>();
        for (ConversationMessage message : page.items()) {
            switch (message.role()) {
                case USER -> history.add("User: " + message.content());
                case ASSISTANT -> history.add("Assistant: " + message.content());
                case TOOL -> {
                    if (Boolean.TRUE.equals(message.metadata().get("displayable"))) {
                        history.add("Tool: " + message.content());
                    }
                }
                case SYSTEM -> {
                    if (message.messageType() == ConversationMessageType.EVENT
                            && Boolean.TRUE.equals(message.metadata().get("displayable"))) {
                        history.add("Event: " + message.content());
                    }
                }
            }
        }
        return List.copyOf(history);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ConversationMemoryException(
                    ConversationErrorCode.MEMORY_PERSISTENCE_DISABLED,
                    "Memory persistence is disabled; Redis fallback is not permitted");
        }
    }

    private static void requireConversationIdentity(String conversationId, String userId) {
        requireText(conversationId, "conversationId");
        requireText(userId, "userId");
        if (conversationId.length() > 64 || userId.length() > 128) {
            throw invalid("Conversation identity exceeds the storage contract");
        }
    }

    private static void validateLimit(int limit) {
        if (limit <= 0 || limit > MAX_PAGE_SIZE) {
            throw invalid("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
    }

    private static ConversationMemoryException notFound() {
        return new ConversationMemoryException(
                ConversationErrorCode.CONVERSATION_NOT_FOUND,
                "Conversation was not found");
    }

    private static ConversationMemoryException invalid(String message) {
        return new ConversationMemoryException(ConversationErrorCode.INVALID_CONVERSATION_REQUEST, message);
    }
}
