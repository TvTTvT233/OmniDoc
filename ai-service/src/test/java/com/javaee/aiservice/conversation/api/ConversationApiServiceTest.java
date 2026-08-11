package com.javaee.aiservice.conversation.api;

import com.javaee.aiservice.conversation.model.Conversation;
import com.javaee.aiservice.conversation.model.ConversationCursor;
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

class ConversationApiServiceTest {

    private ConversationService conversationService;
    private ConversationCursorCodec cursorCodec;
    private ConversationApiService apiService;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        cursorCodec = new ConversationCursorCodec("test-only-cursor-key-with-at-least-32-bytes");
        apiService = new ConversationApiService(
                conversationService,
                cursorCodec,
                new ConversationPublicMessageMapper());
    }

    @Test
    void createNormalizesTitleWithoutAcceptingAnOwnerField() {
        Conversation stored = conversation("conv-1", "authenticated-owner", "A title", ConversationStatus.ACTIVE);
        when(conversationService.createConversation("authenticated-owner", "A title")).thenReturn(stored);

        ConversationResponse response = apiService.create(
                "authenticated-owner", new CreateConversationRequest("  A title  "));

        assertThat(response.conversationId()).isEqualTo("conv-1");
        verify(conversationService).createConversation("authenticated-owner", "A title");
    }

    @Test
    void listDecodesSignedCursorAndEmitsAnOpaqueNextCursor() {
        ConversationCursor inputBoundary = new ConversationCursor(
                Instant.parse("2026-08-11T02:00:00Z"), "conv-2");
        ConversationCursor nextBoundary = new ConversationCursor(
                Instant.parse("2026-08-10T02:00:00Z"), "conv-1");
        when(conversationService.listConversations(
                "owner", ConversationStatus.ARCHIVED, inputBoundary, 5))
                .thenReturn(new ConversationPage(
                        List.of(conversation("conv-1", "owner", null, ConversationStatus.ARCHIVED)),
                        nextBoundary));

        ConversationPageResponse response = apiService.list(
                "owner", "archived", cursorCodec.encode(inputBoundary), "5");

        assertThat(response.hasMore()).isTrue();
        assertThat(cursorCodec.decode(response.nextCursor())).isEqualTo(nextBoundary);
        assertThat(response.items()).extracting(ConversationResponse::conversationId).containsExactly("conv-1");
    }

    @Test
    void invalidFiltersFailBeforeCallingPersistence() {
        assertThatThrownBy(() -> apiService.list("owner", "deleted", null, "20"))
                .isInstanceOfSatisfying(ConversationMemoryException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ConversationErrorCode.INVALID_CONVERSATION_REQUEST));
        assertThatThrownBy(() -> apiService.list("owner", null, null, "201"))
                .isInstanceOf(ConversationMemoryException.class);
        assertThatThrownBy(() -> apiService.get("temp_forged", "owner"))
                .isInstanceOf(ConversationMemoryException.class);
    }

    @Test
    void decodedCursorCarriesOnlyTheFrozenSeekFields() {
        ConversationCursor boundary = new ConversationCursor(
                Instant.parse("2026-08-11T02:00:00Z"), "conv-3");
        when(conversationService.listConversations(
                org.mockito.ArgumentMatchers.eq("owner"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(20)))
                .thenReturn(new ConversationPage(List.of(), null));

        apiService.list("owner", null, cursorCodec.encode(boundary), null);

        ArgumentCaptor<ConversationCursor> cursor = ArgumentCaptor.forClass(ConversationCursor.class);
        verify(conversationService).listConversations(
                org.mockito.ArgumentMatchers.eq("owner"),
                org.mockito.ArgumentMatchers.isNull(),
                cursor.capture(),
                org.mockito.ArgumentMatchers.eq(20));
        assertThat(cursor.getValue()).isEqualTo(boundary);
    }

    private static Conversation conversation(
            String id,
            String userId,
            String title,
            ConversationStatus status
    ) {
        Instant now = Instant.parse("2026-08-11T02:00:00Z");
        return new Conversation(id, userId, title, status, 1, now, now, null, 0);
    }
}
