package com.javaee.documentservice.consistency;

public class DocumentStateException extends RuntimeException {
    private final int status;
    private final String errorCode;
    public DocumentStateException(int status, String errorCode, String message) {
        super(message); this.status = status; this.errorCode = errorCode;
    }
    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
}
