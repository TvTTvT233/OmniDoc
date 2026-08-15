package com.javaee.aiservice.review.m0.extraction;

public class RemoteCandidateClientException extends Exception {
    private final boolean outcomeUnknown;

    public RemoteCandidateClientException(boolean outcomeUnknown, String safeMessage, Throwable cause) {
        super("Remote candidate client failed");
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
