package com.javaee.aiservice.conversation;

import com.javaee.aiservice.conversation.model.Conversation;
import com.javaee.aiservice.conversation.model.ConversationCursor;
import com.javaee.aiservice.conversation.model.ConversationPage;
import com.javaee.aiservice.conversation.model.ConversationStatus;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.conversation.service.ConversationService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compatibility facade for the legacy Agent chat API.
 *
 * <p>Conversation and Message facts are delegated to the JDBC-backed service. Methods without
 * an authenticated user are deliberately rejected because they cannot satisfy the owner contract.</p>
 */
@Component
public class ConversationManager {

    private static final int LEGACY_HISTORY_LIMIT = 100;
    private static final int LEGACY_LIST_LIMIT = 200;

    private final ConversationService conversationService;

    public ConversationManager(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    public String createConversation(String userId) {
        return conversationService.createConversation(userId).conversationId();
    }

    public void addMessageForUser(
            String conversationId,
            String userId,
            String userMessage,
            String assistantMessage
    ) {
        String clientRequestId = UUID.randomUUID().toString();
        String turnId = UUID.randomUUID().toString();
        conversationService.appendExchange(
                conversationId,
                userId,
                clientRequestId,
                turnId,
                userMessage,
                assistantMessage);
    }

    public List<ConversationMessage> appendMessagesForUser(
            String conversationId,
            String userId,
            List<MessageAppendCommand> commands
    ) {
        return conversationService.appendMessages(conversationId, userId, commands);
    }

    public List<String> getConversationHistoryForUser(String conversationId, String userId) {
        return conversationService.legacyHistory(conversationId, userId, LEGACY_HISTORY_LIMIT);
    }

    public void deleteConversationForUser(String conversationId, String userId) {
        conversationService.delete(conversationId, userId);
    }

    public void assertOwner(String conversationId, String userId) {
        conversationService.requireOwned(conversationId, userId);
    }

    public List<String> getUserConversations(String userId) {
        List<String> conversationIds = new ArrayList<>();
        ConversationCursor cursor = null;
        do {
            ConversationPage page = conversationService.listConversations(
                    userId,
                    ConversationStatus.ACTIVE,
                    cursor,
                    LEGACY_LIST_LIMIT);
            conversationIds.addAll(page.items().stream().map(Conversation::conversationId).toList());
            cursor = page.nextCursor();
        } while (cursor != null);
        return List.copyOf(conversationIds);
    }

    public Map<String, Object> getConversationInfoForUser(String conversationId, String userId) {
        Conversation conversation = conversationService.requireOwned(conversationId, userId);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("conversationId", conversation.conversationId());
        info.put("userId", conversation.userId());
        info.put("title", conversation.title());
        info.put("status", conversation.status().name());
        info.put("createdAt", conversation.createdAt());
        info.put("updatedAt", conversation.updatedAt());
        return Collections.unmodifiableMap(info);
    }

    /** @deprecated Ownerless access cannot satisfy Memory V1 authorization. */
    @Deprecated(forRemoval = false)
    public void addMessage(String conversationId, String userMessage, String assistantMessage) {
        throw ownerRequired();
    }

    /** @deprecated Use {@link #getConversationHistoryForUser(String, String)}. */
    @Deprecated(forRemoval = false)
    public List<String> getConversationHistory(String conversationId) {
        throw ownerRequired();
    }

    /** @deprecated Use {@link #deleteConversationForUser(String, String)}. */
    @Deprecated(forRemoval = false)
    public void deleteConversation(String conversationId) {
        throw ownerRequired();
    }

    /** @deprecated Use {@link #getConversationInfoForUser(String, String)}. */
    @Deprecated(forRemoval = false)
    public Map<String, Object> getConversationInfo(String conversationId) {
        throw ownerRequired();
    }

    private static IllegalStateException ownerRequired() {
        return new IllegalStateException("Authenticated userId is required for conversation access");
    }
}
