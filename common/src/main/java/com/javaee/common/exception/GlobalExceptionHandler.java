package com.javaee.common.exception;

import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * @author qxk
 * @description: 全局异常捕获（统一返回）
 * 使用@RestControllerAdvice注解实现全局异常处理，统一返回Result格式的错误信息
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final ConcurrentHashMap<String, AtomicLong> FAILURE_COUNTS = new ConcurrentHashMap<>();

    /**
     * 处理业务异常
     * @param e 业务异常
     * @return 统一返回结果
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        logFailure("GLOBAL_API_BUSINESS_REJECTED", "BUSINESS_RULE_REJECTED", "VALIDATION", false);
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理令牌异常
     * @param e 令牌异常
     * @return 统一返回结果
     */
    @ExceptionHandler(TokenException.class)
    public Result<Void> handleTokenException(TokenException e) {
        logFailure("GLOBAL_API_TOKEN_REJECTED", "TOKEN_INVALID", "AUTHENTICATION", false);
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常（@Valid注解触发）
     * @param e 参数校验异常
     * @return 统一返回结果
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        logFailure("GLOBAL_API_VALIDATION_REJECTED", "REQUEST_VALIDATION_FAILED", "VALIDATION", false);
        return Result.fail(ErrorCodeEnum.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理参数绑定异常
     * @param e 参数绑定异常
     * @return 统一返回结果
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数绑定失败";
        logFailure("GLOBAL_API_BINDING_REJECTED", "REQUEST_BINDING_FAILED", "VALIDATION", false);
        return Result.fail(ErrorCodeEnum.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理非法参数异常
     * @param e 非法参数异常
     * @return 统一返回结果
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        logFailure("GLOBAL_API_ARGUMENT_REJECTED", "REQUEST_ARGUMENT_INVALID", "VALIDATION", false);
        return Result.fail(ErrorCodeEnum.BAD_REQUEST.getCode(), e.getMessage());
    }

    /**
     * 处理所有未捕获的异常（兜底处理）
     * @param e 异常
     * @return 统一返回结果
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        logFailure("GLOBAL_API_FAILED", "INTERNAL_ERROR", "SYSTEM", false);
        return Result.fail(ErrorCodeEnum.INTERNAL_SERVER_ERROR);
    }

    private static void logFailure(
            String event,
            String reasonCode,
            String exceptionCategory,
            boolean retryable
    ) {
        long count = FAILURE_COUNTS.computeIfAbsent(reasonCode, ignored -> new AtomicLong()).incrementAndGet();
        String requestId = safeToken(MDC.get("requestId"));
        String correlationId = safeToken(MDC.get("correlationId"));
        if ("UNAVAILABLE".equals(correlationId)) {
            correlationId = requestId;
        }
        log.error("event={} reasonCode={} requestId={} correlationId={} "
                        + "exceptionCategory={} retryable={} retryCount={} count={}",
                event, reasonCode, requestId, correlationId,
                exceptionCategory, retryable, 0, count);
    }

    private static String safeToken(String value) {
        return value != null && SAFE_TOKEN.matcher(value).matches() ? value : "UNAVAILABLE";
    }
}
