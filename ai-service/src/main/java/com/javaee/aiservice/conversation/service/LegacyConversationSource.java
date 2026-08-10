package com.javaee.aiservice.conversation.service;

import java.util.Optional;

public interface LegacyConversationSource {

    Optional<LegacyConversationSnapshot> findOwned(String conversationId, String userId);
}
