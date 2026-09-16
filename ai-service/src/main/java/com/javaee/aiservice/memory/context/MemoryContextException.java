package com.javaee.aiservice.memory.context;

public final class MemoryContextException extends RuntimeException {

    public static final String CODE = "MEMORY_CONTEXT_TOO_LARGE";

    private final String code;

    public MemoryContextException(String message) {
        super(message);
        this.code = CODE;
    }

    public String getCode() {
        return code;
    }
}
