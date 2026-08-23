package com.javaee.fileservice.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Reads the stable owner only from Spring Security's verified authentication context. */
@Component
public class AuthenticatedFileOwner {
    public String requireOwnerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getPrincipal() == null) {
            throw new SecurityException("Authentication is required");
        }
        String ownerId = authentication.getPrincipal().toString();
        if (ownerId.isBlank() || ownerId.length() > 128
                || "anonymous".equalsIgnoreCase(ownerId)
                || "anonymousUser".equalsIgnoreCase(ownerId)) {
            throw new SecurityException("Authentication is required");
        }
        return ownerId;
    }
}
