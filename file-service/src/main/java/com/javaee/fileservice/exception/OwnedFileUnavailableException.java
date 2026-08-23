package com.javaee.fileservice.exception;

/** System/storage/integrity failure that must not be represented as a business absence. */
public class OwnedFileUnavailableException extends RuntimeException {
    public OwnedFileUnavailableException(String message) {
        super(message);
    }

    public OwnedFileUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
