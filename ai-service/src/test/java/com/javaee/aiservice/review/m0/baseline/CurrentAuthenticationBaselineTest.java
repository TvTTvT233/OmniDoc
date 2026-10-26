package com.javaee.aiservice.review.m0.baseline;

import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.common.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentAuthenticationBaselineTest {

    private final RequestUserContext requestUserContext = new RequestUserContext();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void jwtUserIdClaimBecomesTheStableStringPrincipalUsedByAiService() throws Exception {
        String token = JwtUtils.generateToken(42L, "synthetic-user", "user");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        new JwtAuthenticationFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                            .isEqualTo(42L);
                    assertThat(requestUserContext.getRequiredUserId()).isEqualTo("42");
                    assertThat(requestUserContext.getCurrentRole()).isEqualTo("user");
                });
    }

    @Test
    void requestUserContextUsesAuthenticatedPrincipalAndRejectsMissingAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "stable-owner-id",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))));

        assertThat(requestUserContext.getRequiredUserId()).isEqualTo("stable-owner-id");

        SecurityContextHolder.clearContext();
        assertThat(requestUserContext.getCurrentUserId()).isEmpty();
        assertThatThrownBy(requestUserContext::getRequiredUserId)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("未认证");
    }

    @Test
    void invalidBearerTokenDoesNotCreateAnAuthenticatedPrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer synthetic-invalid-token");

        new JwtAuthenticationFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                    assertThat(requestUserContext.getCurrentUserId()).isEmpty();
                });
    }
}
