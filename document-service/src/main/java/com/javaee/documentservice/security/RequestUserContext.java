package com.javaee.documentservice.security;

import com.javaee.documentservice.consistency.DocumentStateException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the current user exclusively from the verified JWT security context.
 */
@Component
public class RequestUserContext {

    public Long getRequiredUserId() {
        return getCurrentUserId()
                .orElseThrow(() -> new DocumentStateException(401,"UNAUTHORIZED","请先登录"));
    }

    public Optional<Long> getCurrentUserId() {
        Optional<Long> principalUserId = userIdFromPrincipal();
        if (principalUserId.isPresent()) {
            return principalUserId;
        }
        return Optional.empty();
    }

    private Optional<Long> userIdFromPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getPrincipal() == null) {
            return Optional.empty();
        }
        return parseUserId(authentication.getPrincipal().toString());
    }

    private Optional<Long> parseUserId(String raw) {
        if (raw == null || raw.isBlank() || "anonymousUser".equalsIgnoreCase(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
