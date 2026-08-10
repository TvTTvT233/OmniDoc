package com.javaee.aiservice.conversation.runtime;

/**
 * Stable Runtime State error boundary for later Controller/Agent integration.
 */
public class RuntimeStateException extends RuntimeException {

    private final String code;

    protected RuntimeStateException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected RuntimeStateException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static final class Unavailable extends RuntimeStateException {
        public static final String CODE = "RUNTIME_STATE_UNAVAILABLE";

        public Unavailable(String operation, Throwable cause) {
            super(CODE, "Runtime State is unavailable for operation: " + operation, cause);
        }
    }

    public static final class Invalid extends RuntimeStateException {
        public static final String CODE = "RUNTIME_STATE_INVALID";

        public Invalid(String message) {
            super(CODE, message);
        }

        public Invalid(String message, Throwable cause) {
            super(CODE, message, cause);
        }
    }
}
