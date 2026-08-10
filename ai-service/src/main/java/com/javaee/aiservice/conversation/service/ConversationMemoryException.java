package com.javaee.aiservice.conversation.service;

public class ConversationMemoryException extends RuntimeException {

    private final ConversationErrorCode code;

    public ConversationMemoryException(ConversationErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ConversationMemoryException(ConversationErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ConversationErrorCode getCode() {
        return code;
    }
}
