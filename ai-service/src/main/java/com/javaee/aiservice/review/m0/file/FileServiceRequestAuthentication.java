package com.javaee.aiservice.review.m0.file;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Binds an outbound file-service call to the verified user and bearer token of this request. */
final class FileServiceRequestAuthentication {
    String requireBearerForOwner(String ownerId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getPrincipal() == null) {
            throw unavailable("Authenticated file-service delegation is unavailable");
        }
        String authenticatedOwner = authentication.getPrincipal().toString();
        if (!ownerId.equals(authenticatedOwner)) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.NOT_FOUND, "Owned file does not exist");
        }
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            throw unavailable("Authenticated file-service delegation is unavailable");
        }
        String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring("Bearer ".length()).isBlank()) {
            throw unavailable("Authenticated file-service delegation is unavailable");
        }
        return authorization;
    }

    private static OwnedFileAccessException unavailable(String message) {
        return new OwnedFileAccessException(
                OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE, message);
    }
}
