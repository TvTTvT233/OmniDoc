package com.javaee.aiservice.conversation.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Client-supplied creation fields. Ownership is deliberately absent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateConversationRequest(String title) {
}
