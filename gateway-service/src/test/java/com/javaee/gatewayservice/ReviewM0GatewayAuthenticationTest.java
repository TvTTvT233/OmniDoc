package com.javaee.gatewayservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.gateway.filter.AuthGlobalFilter;
import com.javaee.gateway.util.RabbitMQUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReviewM0GatewayAuthenticationTest {
    @Test
    void missingBearerCannotUseSpoofedOwnerAndRetainsReviewErrorContract() throws Exception {
        var filter = new AuthGlobalFilter();
        ReflectionTestUtils.setField(filter, "rabbitMQUtil", mock(RabbitMQUtil.class));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/review-m0/cases")
                .header("X-User-Id", "999").header("X-Role", "admin"));
        var forwarded = new AtomicBoolean();
        filter.filter(exchange, value -> { forwarded.set(true); return Mono.empty(); }).block();
        assertFalse(forwarded.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        var body = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertEquals(4, body.size());
        assertEquals("AUTHENTICATION_REQUIRED", body.get("errorCode").asText());
        assertFalse(body.get("requestId").asText().isBlank());
        assertTrue(body.get("details").isObject());
    }

    @Test
    void existingNonReviewUnauthorizedResponseRemainsEmpty() {
        var filter = new AuthGlobalFilter();
        ReflectionTestUtils.setField(filter, "rabbitMQUtil", mock(RabbitMQUtil.class));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents/example"));
        filter.filter(exchange, value -> { fail("Unauthorized request was forwarded"); return Mono.empty(); }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("", exchange.getResponse().getBodyAsString().block());
    }
}
