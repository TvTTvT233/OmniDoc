package com.javaee.fileservice.exception;

/** Deliberately shared by missing, cross-owner, and owner-null historical records. */
public class OwnedFileNotFoundException extends RuntimeException {
    public OwnedFileNotFoundException() {
        super("Owned file does not exist");
    }

    public OwnedFileNotFoundException(Throwable cause) {
        super("Owned file does not exist", cause);
    }
}
