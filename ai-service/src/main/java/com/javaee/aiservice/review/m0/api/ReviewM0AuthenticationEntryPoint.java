package com.javaee.aiservice.review.m0.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.javaee.aiservice.review.m0.api.ReviewM0ApiDtos.ErrorResponse;

/** Writes the frozen Review M0 response when the production security chain has no valid principal. */
@Component
public final class ReviewM0AuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper json;

    public ReviewM0AuthenticationEntryPoint(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), new ErrorResponse(
                ReviewM0ErrorCode.AUTHENTICATION_REQUIRED,
                "Authentication is required",
                ReviewM0RequestIdFilter.getOrCreateRequestId(request),
                Map.of()));
    }
}
