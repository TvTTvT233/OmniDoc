package com.javaee.aiservice.conversation.runtime;

/**
 * Conversation-lifecycle boundary for clearing every short-lived derivative before durable delete.
 *
 * <p>The production implementation covers Runtime State, Agent task snapshots and pending approval
 * references. Keeping this boundary outside {@code ConversationService} prevents the durable store
 * from depending on Redis implementation details.</p>
 */
@FunctionalInterface
public interface RuntimeStateCleanup {

    void clearConversation(String conversationId);
}
