package com.javaee.aiservice.review.m0.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.conversation.api.ConversationApiErrorResponse;
import com.javaee.aiservice.conversation.api.ConversationApiExceptionHandler;
import com.javaee.aiservice.conversation.runtime.RuntimeStateException;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentStructuredApiBaselineTest {

    private final ConversationApiExceptionHandler handler = new ConversationApiExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void currentStructuredCarrierContainsExactlyErrorCodeAndMessage() {
        JsonNode body = objectMapper.valueToTree(
                new ConversationApiErrorResponse("SYNTHETIC_CODE", "Synthetic message"));

        assertThat(java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(body.fieldNames(), 0), false).toList())
                .containsExactlyInAnyOrder("errorCode", "message");
        assertThat(body.path("requestId").isMissingNode()).isTrue();
        assertThat(body.path("details").isMissingNode()).isTrue();
    }

    @Test
    void currentHandlerMaps400AndTransformsInvalidRequestPublicCode() {
        ResponseEntity<ConversationApiErrorResponse> response = handler.handleConversationError(
                error(ConversationErrorCode.INVALID_CONVERSATION_REQUEST));

        assertResponse(response, HttpStatus.BAD_REQUEST, "CONVERSATION_REQUEST_INVALID");
    }

    @Test
    void currentHandlerMaps401And404() {
        assertResponse(
                handler.handleAuthentication(new SecurityException("synthetic")),
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED");
        assertResponse(
                handler.handleConversationError(error(ConversationErrorCode.CONVERSATION_NOT_FOUND)),
                HttpStatus.NOT_FOUND,
                "CONVERSATION_NOT_FOUND");
    }

    @Test
    void currentHandlerMaps409CodesWithoutCollapsingThem() {
        Set<ConversationErrorCode> conflicts = Set.of(
                ConversationErrorCode.CONVERSATION_ARCHIVED,
                ConversationErrorCode.CONVERSATION_CONFLICT);

        for (ConversationErrorCode code : conflicts) {
            assertResponse(handler.handleConversationError(error(code)), HttpStatus.CONFLICT, code.name());
        }
    }

    @Test
    void currentHandlerMaps503And500ToStablePublicBodies() {
        assertResponse(
                handler.handleRuntimeState(new RuntimeStateException.Unavailable(
                        "synthetic-operation",
                        new IllegalStateException("synthetic-cause"))),
                HttpStatus.SERVICE_UNAVAILABLE,
                "RUNTIME_STATE_UNAVAILABLE");
        assertResponse(
                handler.handleUnexpected(new IllegalStateException("synthetic-internal")),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "CONVERSATION_REQUEST_FAILED");
    }

    private static ConversationMemoryException error(ConversationErrorCode code) {
        return new ConversationMemoryException(code, "synthetic message");
    }

    private static void assertResponse(
            ResponseEntity<ConversationApiErrorResponse> response,
            HttpStatus expectedStatus,
            String expectedCode
    ) {
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo(expectedCode);
        assertThat(response.getBody().message()).isNotBlank();
    }
}
