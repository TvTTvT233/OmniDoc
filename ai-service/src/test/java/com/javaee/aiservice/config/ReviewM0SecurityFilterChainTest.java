package com.javaee.aiservice.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.api.ReviewM0AuthenticationEntryPoint;
import com.javaee.aiservice.review.m0.api.ReviewM0RequestIdFilter;
import com.javaee.aiservice.review.m0.config.ReviewM0RequestIdConfiguration;
import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.common.utils.JwtUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReviewM0SecurityFilterChainTest.SecurityProbeController.class)
@ContextConfiguration(classes = ReviewM0SecurityFilterChainTest.SecurityProbeController.class)
@ImportAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        ReviewM0AuthenticationEntryPoint.class,
        ReviewM0RequestIdConfiguration.class,
        RequestUserContext.class
})
class ReviewM0SecurityFilterChainTest {

    private static final String REVIEW_CASE = "/api/review-m0/security-probe/cases/case-owned";
    private static final String REVIEW_DOWNLOAD =
            "/api/review-m0/cases/case-owned/materials/material-owned/download";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private FilterRegistrationBean<ReviewM0RequestIdFilter> requestIdRegistration;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAuthentication")
    void everyMissingOrInvalidAuthenticationFormUsesTheSameFrozen401Envelope(
            String scenario,
            String authorization
    ) throws Exception {
        MockHttpServletRequestBuilder request = get(REVIEW_CASE);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        MvcResult result = mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.matchesPattern(
                        "req_[0-9a-f]{32}")))
                .andExpect(jsonPath("$.details").isEmpty())
                .andReturn();

        assertThat(result.getResponse().getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "errorCode", "message", "requestId", "details");
        assertThat(body.toString().toLowerCase()).doesNotContain(
                "token", "jwt", "signature", "expired", "malformed", "exception", "stack");
        if (authorization != null && authorization.length() > 16) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(authorization.substring(7));
        }

        MockHttpServletRequestBuilder download = get(REVIEW_DOWNLOAD);
        if (authorization != null) {
            download.header("Authorization", authorization);
        }
        MvcResult downloadResult = mvc.perform(download)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andReturn();
        assertThat(downloadResult.getResponse().getHeader("ETag")).isNull();
        assertThat(downloadResult.getResponse().getHeader("Digest")).isNull();
        assertThat(downloadResult.getResponse().getHeader("X-Content-SHA256")).isNull();
    }

    @Test
    void validJwtReachesTheControllerAndOwnerIsolationRemains404() throws Exception {
        mvc.perform(get(REVIEW_CASE).header("Authorization", bearer(101L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-owned"))
                .andExpect(jsonPath("$.owner").value("101"));

        mvc.perform(get(REVIEW_CASE).header("Authorization", bearer(202L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void requestIdFilterRunsOutsideSecurityAndTheEntryPointReusesItsCorrelationId() throws Exception {
        assertThat(requestIdRegistration.getOrder())
                .isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        assertThat(requestIdRegistration.getUrlPatterns()).containsExactly("/api/review-m0/*");

        mvc.perform(get(REVIEW_CASE).requestAttr(
                        ReviewM0RequestIdFilter.ATTRIBUTE,
                        "req_0123456789abcdef0123456789abcdef"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").value("req_0123456789abcdef0123456789abcdef"));
    }

    @Test
    void productionChainKeepsJwtBeforeAnonymousAndAuthorizationProcessing() {
        List<Class<?>> filterTypes = springSecurityFilterChain.getFilters(REVIEW_CASE).stream()
                .map(Object::getClass)
                .toList();

        assertThat(filterTypes).contains(
                JwtAuthenticationFilter.class,
                AnonymousAuthenticationFilter.class,
                ExceptionTranslationFilter.class,
                AuthorizationFilter.class);
        assertThat(filterTypes.indexOf(JwtAuthenticationFilter.class))
                .isLessThan(filterTypes.indexOf(AnonymousAuthenticationFilter.class));
        assertThat(filterTypes.indexOf(ExceptionTranslationFilter.class))
                .isLessThan(filterTypes.indexOf(AuthorizationFilter.class));
    }

    @Test
    void permitAllSurfaceIsExactAndAdjacentPathsStayProtected() throws Exception {
        for (String path : List.of(
                "/actuator/health",
                "/swagger-ui.html",
                "/swagger-ui/index.html",
                "/v3/api-docs",
                "/api-docs",
                "/error",
                "/",
                "/index.html",
                "/agent-workbench.html",
                "/static/missing.js",
                "/api/ai/agent/tools",
                "/ws/probe")) {
            int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isNotIn(401, 403);
        }

        for (String path : List.of(
                "/actuator-probe",
                "/swagger-ui-probe",
                "/v3/api-docs-probe",
                "/api-docs-probe",
                "/agent-workbench.html-probe",
                "/static-probe/missing.js",
                "/api/ai/agent-probe/tools",
                "/ws-probe",
                "/api/skills/description/probe")) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }
    }

    @Test
    void nonReviewProtectedEndpointsKeepTheirExistingEntryPointBehavior() throws Exception {
        mvc.perform(get("/api/ai/conversations"))
                .andExpect(status().isForbidden());
    }

    private static Stream<Arguments> invalidAuthentication() throws Exception {
        return Stream.of(
                Arguments.of("missing Authorization header", null),
                Arguments.of("empty Bearer value", "Bearer "),
                Arguments.of("non-Bearer scheme", "Basic dXNlcjpwYXNz"),
                Arguments.of("wrong signature", "Bearer " + wrongSignatureToken()),
                Arguments.of("expired token", "Bearer " + expiredToken()),
                Arguments.of("malformed token", "Bearer not-a-jwt"),
                Arguments.of("JWT parsing exception", "Bearer a.b.c"));
    }

    private static String bearer(long userId) {
        return "Bearer " + JwtUtils.generateToken(userId, "synthetic-owner", "user");
    }

    private static String wrongSignatureToken() {
        SecretKey key = Keys.hmacShaKeyFor(
                "different-synthetic-signing-key-for-review-m0-test-only"
                        .getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .claim("userId", 101L)
                .claim("role", "user")
                .setExpiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(key)
                .compact();
    }

    private static String expiredToken() throws Exception {
        Field keyField = JwtUtils.class.getDeclaredField("KEY");
        keyField.setAccessible(true);
        SecretKey key = (SecretKey) keyField.get(null);
        return Jwts.builder()
                .claim("userId", 101L)
                .claim("role", "user")
                .setIssuedAt(Date.from(Instant.now().minusSeconds(600)))
                .setExpiration(Date.from(Instant.now().minusSeconds(300)))
                .signWith(key)
                .compact();
    }

    private static Set<String> fieldNames(JsonNode node) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @RestController
    static class SecurityProbeController {
        private final RequestUserContext users;

        SecurityProbeController(RequestUserContext users) {
            this.users = users;
        }

        @GetMapping("/api/review-m0/security-probe/cases/{caseId}")
        ResponseEntity<Map<String, String>> getCase(@PathVariable String caseId) {
            String owner = users.getRequiredUserId();
            if (!"101".equals(owner) || !"case-owned".equals(caseId)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("caseId", caseId, "owner", owner));
        }
    }
}
