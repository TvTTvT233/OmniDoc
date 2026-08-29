package com.javaee.aiservice.security;

import com.javaee.aiservice.logging.SafeDiagnosticLogger;
import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.model.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts ai-service authorization failures to a stable API response.
 */
@RestControllerAdvice(basePackages = "com.javaee.aiservice")
public class AiSecurityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiSecurityExceptionHandler.class);

    @ExceptionHandler(SecurityException.class)
    public Result<Void> handleSecurityException(SecurityException e) {
        SafeDiagnosticLogger.warn(log, "AI_AUTHORIZATION_REJECTED", "AUTHORIZATION_FAILED",
                e, false, 0, 1);
        return Result.fail(ErrorCodeEnum.FORBIDDEN.getCode(), e.getMessage());
    }
}
