package com.javaee.aiservice.conversation.service;

import java.util.Optional;

public interface LegacyConversationSource {

    Optional<LegacyConversationSnapshot> findOwned(String conversationId, String userId);

    /**
     * Removes a legacy source after an owner-authorized permanent delete.
     *
     * <p>Sources without durable legacy data can keep the default. Implementations that can
     * re-import a deleted conversation must override this method and fail explicitly when cleanup
     * cannot be completed.</p>
     */
    default void deleteOwned(String conversationId, String userId) {
        // No durable legacy source.
    }
}
