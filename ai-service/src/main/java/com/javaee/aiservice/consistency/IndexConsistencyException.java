package com.javaee.aiservice.consistency;

public final class IndexConsistencyException extends RuntimeException {
    private final String code;
    public IndexConsistencyException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
