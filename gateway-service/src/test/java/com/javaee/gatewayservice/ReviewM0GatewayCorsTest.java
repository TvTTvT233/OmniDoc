package com.javaee.gatewayservice;

import com.javaee.gateway.config.GlobalCorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0GatewayCorsTest {
    @Test
    void patchPreflightAndActualRequestAreAllowedOnlyForExistingOrigin() {
        var filter = new GlobalCorsConfig().corsWebFilter();
        var calls = new AtomicInteger();
        var preflight = MockServerWebExchange.from(MockServerHttpRequest.options("http://localhost:8080/api/review-m0/cases/case-a")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"));
        filter.filter(preflight, exchange -> { calls.incrementAndGet(); return Mono.empty(); }).block();
        assertThat(preflight.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(preflight.getResponse().getHeaders().getAccessControlAllowMethods())
                .contains(org.springframework.http.HttpMethod.PATCH);
        assertThat(calls).hasValue(0);

        var actual = MockServerWebExchange.from(MockServerHttpRequest.patch("http://localhost:8080/api/review-m0/cases/case-a")
                .header("Origin", "http://localhost:5173"));
        filter.filter(actual, exchange -> { calls.incrementAndGet(); return Mono.empty(); }).block();
        assertThat(calls).hasValue(1); // Authentication remains the responsibility of the downstream security filter.
        assertThat(actual.getResponse().getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");

        var forbidden = MockServerWebExchange.from(MockServerHttpRequest.patch("http://localhost:8080/api/review-m0/cases/case-a")
                .header("Origin", "https://untrusted.invalid"));
        filter.filter(forbidden, exchange -> { calls.incrementAndGet(); return Mono.empty(); }).block();
        assertThat(forbidden.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(calls).hasValue(1);
    }
}
