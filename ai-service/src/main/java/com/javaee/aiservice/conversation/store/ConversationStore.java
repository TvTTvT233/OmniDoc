package com.javaee.aiservice.conversation.store;

import com.javaee.aiservice.conversation.model.Conversation;
import com.javaee.aiservice.conversation.model.ConversationCursor;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessagePage;
import com.javaee.aiservice.conversation.model.ConversationPage;
import com.javaee.aiservice.conversation.model.ConversationStatus;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;

import java.util.List;
import java.util.Optional;

public interface ConversationStore {

    Conversation create(Conversation conversation);

    Optional<Conversation> findOwned(String conversationId, String userId);

    ConversationPage listOwned(String userId, ConversationStatus status, ConversationCursor cursor, int limit);

    ConversationMessagePage listMessagesOwned(String conversationId, String userId, Long beforeSequence, int limit);

    List<ConversationMessage> listMessagesAfterOwned(
            String conversationId,
            String userId,
            long afterSequence,
            int limit
    );

    List<ConversationMessage> appendMessages(
            String conversationId,
            String userId,
            List<MessageAppendCommand> commands
    );

    Conversation importLegacyConversation(Conversation conversation, List<MessageAppendCommand> commands);

    Conversation changeStatus(
            String conversationId,
            String userId,
            ConversationStatus expectedStatus,
            ConversationStatus targetStatus
    );

    void deleteOwned(String conversationId, String userId);
}
