package com.javaee.fileservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Read-only service credential, independent of user JWTs and public gateway headers. */
public final class InternalReviewFileAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] token;

    public InternalReviewFileAuthenticationFilter(String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/internal/review-files/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader("X-OmniDoc-Review-Token");
        int status = token.length < 32 ? 503 : provided != null && provided.length() <= 1024
                && MessageDigest.isEqual(token, provided.getBytes(StandardCharsets.UTF_8)) ? 200 : 401;
        if (status != 200) {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(status == 503
                    ? "{\"errorCode\":\"INTERNAL_CHANNEL_DISABLED\",\"message\":\"Internal channel is unavailable\"}"
                    : "{\"errorCode\":\"INTERNAL_UNAUTHORIZED\",\"message\":\"Internal authentication is required\"}");
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            response.setStatus(405);
            return;
        }
        chain.doFilter(request, response);
    }
}
