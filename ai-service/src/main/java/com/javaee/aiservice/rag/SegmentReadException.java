package com.javaee.aiservice.rag;

public class SegmentReadException extends RuntimeException {
    private final int status;

    public SegmentReadException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
