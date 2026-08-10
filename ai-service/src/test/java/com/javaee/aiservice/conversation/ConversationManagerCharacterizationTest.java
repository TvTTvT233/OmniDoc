package com.javaee.aiservice.conversation;

import com.javaee.aiservice.conversation.model.Conversation;
import com.javaee.aiservice.conversation.model.ConversationPage;
import com.javaee.aiservice.conversation.model.ConversationStatus;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import com.javaee.aiservice.conversation.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationManagerCharacterizationTest {

    private ConversationService conversationService;
    private ConversationManager manager;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        manager = new ConversationManager(conversationService);
    }

    @Test
    void legacyCreateApiDelegatesToThePersistentConversationService() {
        Conversation conversation = conversation("conv-1", "owner", ConversationStatus.ACTIVE);
        when(conversationService.createConversation("owner")).thenReturn(conversation);

        assertThat(manager.createConversation("owner")).isEqualTo("conv-1");
        verify(conversationService).createConversation("owner");
    }

    @Test
    void legacyAppendApiSuppliesServerIdentifiersAndAuthenticatedOwner() {
        manager.addMessageForUser("conv-1", "owner", "question", "answer");

        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> turnId = ArgumentCaptor.forClass(String.class);
        verify(conversationService).appendExchange(
                org.mockito.ArgumentMatchers.eq("conv-1"),
                org.mockito.ArgumentMatchers.eq("owner"),
                requestId.capture(),
                turnId.capture(),
                org.mockito.ArgumentMatchers.eq("question"),
                org.mockito.ArgumentMatchers.eq("answer"));
        assertThat(requestId.getValue()).isNotBlank();
        assertThat(turnId.getValue()).isNotBlank();
    }

    @Test
    void legacyHistoryAndDeleteApisKeepTheOwnerBoundary() {
        when(conversationService.legacyHistory("conv-1", "owner", 100))
                .thenReturn(List.of("User: question", "Assistant: answer"));

        assertThat(manager.getConversationHistoryForUser("conv-1", "owner"))
                .containsExactly("User: question", "Assistant: answer");
        manager.deleteConversationForUser("conv-1", "owner");

        verify(conversationService).legacyHistory("conv-1", "owner", 100);
        verify(conversationService).delete("conv-1", "owner");
    }

    @Test
    void ownerFailureIsPropagatedWithoutASecondRedisAuthorizationPath() {
        ConversationMemoryException notFound = new ConversationMemoryException(
                ConversationErrorCode.CONVERSATION_NOT_FOUND,
                "Conversation was not found");
        when(conversationService.requireOwned("conv-1", "other")).thenThrow(notFound);

        assertThatThrownBy(() -> manager.assertOwner("conv-1", "other"))
                .isSameAs(notFound);
    }

    @Test
    void listUsesActivePersistentConversationsAndOwnerlessLegacyMethodsAreRejected() {
        when(conversationService.listConversations("owner", ConversationStatus.ACTIVE, null, 200))
                .thenReturn(new ConversationPage(List.of(
                        conversation("conv-2", "owner", ConversationStatus.ACTIVE),
                        conversation("conv-1", "owner", ConversationStatus.ACTIVE)), null));

        assertThat(manager.getUserConversations("owner")).containsExactly("conv-2", "conv-1");
        assertThatThrownBy(() -> manager.getConversationHistory("conv-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authenticated userId");
        assertThatThrownBy(() -> manager.addMessage("conv-1", "question", "answer"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> manager.deleteConversation("conv-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Conversation conversation(String id, String userId, ConversationStatus status) {
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        return new Conversation(id, userId, "title", status, 1, now, now, null, 0);
    }
}
