package com.javaee.aiservice.conversation.api;

import com.javaee.aiservice.conversation.model.Conversation;
import com.javaee.aiservice.conversation.model.ConversationCursor;
import com.javaee.aiservice.conversation.model.ConversationMessagePage;
import com.javaee.aiservice.conversation.model.ConversationPage;
import com.javaee.aiservice.conversation.model.ConversationStatus;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import com.javaee.aiservice.conversation.service.ConversationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Resource-layer orchestration without changing the M02 persistence semantics. */
@Service
public final class ConversationApiService {

    static final int DEFAULT_PAGE_SIZE = 20;
    private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final ConversationService conversationService;
    private final ConversationCursorCodec cursorCodec;
    private final ConversationPublicMessageMapper publicMessageMapper;

    public ConversationApiService(
            ConversationService conversationService,
            ConversationCursorCodec cursorCodec,
            ConversationPublicMessageMapper publicMessageMapper
    ) {
        this.conversationService = conversationService;
        this.cursorCodec = cursorCodec;
        this.publicMessageMapper = publicMessageMapper;
    }

    public ConversationResponse create(String ownerUserId, CreateConversationRequest request) {
        String title = request == null ? null : request.title();
        Conversation conversation = title == null || title.trim().isEmpty()
                ? conversationService.createConversation(ownerUserId)
                : conversationService.createConversation(ownerUserId, title.trim());
        return toResponse(conversation);
    }

    public ConversationPageResponse list(
            String ownerUserId,
            String requestedStatus,
            String encodedCursor,
            String requestedLimit
    ) {
        ConversationStatus status = parseStatus(requestedStatus);
        ConversationCursor cursor = encodedCursor == null || encodedCursor.isBlank()
                ? null
                : cursorCodec.decode(encodedCursor);
        int limit = parseLimit(requestedLimit);
        ConversationPage page = conversationService.listConversations(ownerUserId, status, cursor, limit);
        String nextCursor = page.nextCursor() == null ? null : cursorCodec.encode(page.nextCursor());
        return new ConversationPageResponse(
                page.items().stream().map(ConversationApiService::toResponse).toList(),
                nextCursor,
                nextCursor != null);
    }

    public ConversationResponse get(String conversationId, String ownerUserId) {
        requireConversationId(conversationId);
        return toResponse(conversationService.requireOwned(conversationId, ownerUserId));
    }

    public ConversationMessagePageResponse listMessages(
            String conversationId,
            String ownerUserId,
            String requestedBeforeSequence,
            String requestedLimit
    ) {
        requireConversationId(conversationId);
        Long beforeSequence = parseBeforeSequence(requestedBeforeSequence);
        int limit = parseLimit(requestedLimit);
        ConversationMessagePage page = conversationService.listMessages(
                conversationId, ownerUserId, beforeSequence, limit);
        List<PublicConversationMessageResponse> publicMessages = page.items().stream()
                .map(publicMessageMapper::toPublic)
                .flatMap(java.util.Optional::stream)
                .toList();
        return new ConversationMessagePageResponse(
                publicMessages,
                page.nextBeforeSequence(),
                page.nextBeforeSequence() != null);
    }

    public ConversationResponse archive(String conversationId, String ownerUserId) {
        requireConversationId(conversationId);
        return toResponse(conversationService.archive(conversationId, ownerUserId));
    }

    public ConversationResponse restore(String conversationId, String ownerUserId) {
        requireConversationId(conversationId);
        return toResponse(conversationService.restore(conversationId, ownerUserId));
    }

    public DeleteConversationResponse delete(String conversationId, String ownerUserId) {
        requireConversationId(conversationId);
        conversationService.delete(conversationId, ownerUserId);
        return new DeleteConversationResponse(true);
    }

    private static ConversationResponse toResponse(Conversation conversation) {
        return new ConversationResponse(
                conversation.conversationId(),
                conversation.title(),
                conversation.status().name(),
                conversation.createdAt().toString(),
                conversation.updatedAt().toString());
    }

    private static ConversationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ConversationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw invalid("status must be ACTIVE or ARCHIVED");
        }
    }

    private static int parseLimit(String requestedLimit) {
        if (requestedLimit == null || requestedLimit.isBlank()) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            int limit = Integer.parseInt(requestedLimit);
            if (limit < 1 || limit > ConversationService.MAX_PAGE_SIZE) {
                throw invalid("limit must be between 1 and " + ConversationService.MAX_PAGE_SIZE);
            }
            return limit;
        } catch (NumberFormatException error) {
            throw invalid("limit must be a positive integer");
        }
    }

    private static Long parseBeforeSequence(String requestedBeforeSequence) {
        if (requestedBeforeSequence == null || requestedBeforeSequence.isBlank()) {
            return null;
        }
        try {
            long beforeSequence = Long.parseLong(requestedBeforeSequence);
            if (beforeSequence < 1) {
                throw invalid("beforeSequence must be a positive integer");
            }
            return beforeSequence;
        } catch (NumberFormatException error) {
            throw invalid("beforeSequence must be a positive integer");
        }
    }

    private static void requireConversationId(String conversationId) {
        if (conversationId == null
                || !CONVERSATION_ID.matcher(conversationId).matches()
                || "default_conv".equals(conversationId)
                || conversationId.startsWith("temp_")) {
            throw invalid("conversationId is invalid");
        }
    }

    private static ConversationMemoryException invalid(String message) {
        return new ConversationMemoryException(ConversationErrorCode.INVALID_CONVERSATION_REQUEST, message);
    }
}
