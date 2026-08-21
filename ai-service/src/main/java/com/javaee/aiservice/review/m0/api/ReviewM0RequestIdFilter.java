package com.javaee.aiservice.review.m0.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

public final class ReviewM0RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = ReviewM0RequestIdFilter.class.getName() + ".requestId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/review-m0");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        request.setAttribute(ATTRIBUTE, "req_" + UUID.randomUUID().toString().replace("-", ""));
        filterChain.doFilter(request, response);
    }
}
