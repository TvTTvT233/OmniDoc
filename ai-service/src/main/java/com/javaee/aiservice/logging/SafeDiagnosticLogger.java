package com.javaee.aiservice.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.core.AuthenticationException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Emits bounded diagnostic events without exception messages, exception objects or stack traces.
 */
public final class SafeDiagnosticLogger {

    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "DATABASE", "REDIS", "TIMEOUT", "AUTHENTICATION", "CONCURRENCY",
            "VALIDATION", "IO", "REMOTE_SERVICE", "SYSTEM");

    private SafeDiagnosticLogger() {
    }

    public static void warn(
            Logger logger,
            String event,
            String reasonCode,
            Throwable failure,
            boolean retryable,
            long retryCount,
            long count
    ) {
        warn(logger, event, reasonCode, failure, retryable, retryCount, count, null, null);
    }

    public static void warn(
            Logger logger,
            String event,
            String reasonCode,
            Throwable failure,
            boolean retryable,
            long retryCount,
            long count,
            String requestId,
            String correlationId
    ) {
        write(logger, false, event, reasonCode, failure, retryable, retryCount, count,
                requestId, correlationId);
    }

    public static void error(
            Logger logger,
            String event,
            String reasonCode,
            Throwable failure,
            boolean retryable,
            long retryCount,
            long count,
            String requestId,
            String correlationId
    ) {
        write(logger, true, event, reasonCode, failure, retryable, retryCount, count,
                requestId, correlationId);
    }

    public static String categoryOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof RedisConnectionFailureException) {
                return "REDIS";
            }
            if (current instanceof ConcurrencyFailureException) {
                return "CONCURRENCY";
            }
            if (current instanceof DataAccessException || current instanceof SQLException) {
                return "DATABASE";
            }
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return "TIMEOUT";
            }
            if (current instanceof AuthenticationException || current instanceof SecurityException) {
                return "AUTHENTICATION";
            }
            if (current instanceof IllegalArgumentException) {
                return "VALIDATION";
            }
            if (current instanceof IOException) {
                return "IO";
            }
            String packageName = current.getClass().getPackageName().toLowerCase(Locale.ROOT);
            if (packageName.startsWith("feign.")
                    || packageName.startsWith("org.springframework.web.client")
                    || packageName.startsWith("reactor.netty.http.client")) {
                return "REMOTE_SERVICE";
            }
        }
        return "SYSTEM";
    }

    private static void write(
            Logger logger,
            boolean error,
            String event,
            String reasonCode,
            Throwable failure,
            boolean retryable,
            long retryCount,
            long count,
            String requestId,
            String correlationId
    ) {
        String category = categoryOf(failure);
        if (!ALLOWED_CATEGORIES.contains(category)) {
            category = "SYSTEM";
        }
        String safeEvent = safeToken(event, "UNCLASSIFIED_EVENT");
        String safeReason = safeToken(reasonCode, "UNCLASSIFIED_FAILURE");
        String safeRequestId = safeToken(firstPresent(requestId, MDC.get("requestId")), "UNAVAILABLE");
        String safeCorrelationId = safeToken(
                firstPresent(correlationId, MDC.get("correlationId"), safeRequestId), "UNAVAILABLE");
        long safeRetryCount = Math.max(0, retryCount);
        long safeCount = Math.max(1, count);
        String template = "event={} reasonCode={} requestId={} correlationId={} "
                + "exceptionCategory={} retryable={} retryCount={} count={}";
        if (error) {
            logger.error(template, safeEvent, safeReason, safeRequestId, safeCorrelationId,
                    category, retryable, safeRetryCount, safeCount);
        } else {
            logger.warn(template, safeEvent, safeReason, safeRequestId, safeCorrelationId,
                    category, retryable, safeRetryCount, safeCount);
        }
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String safeToken(String value, String fallback) {
        if (value == null || !SAFE_TOKEN.matcher(value).matches()) {
            return fallback;
        }
        return value;
    }
}
