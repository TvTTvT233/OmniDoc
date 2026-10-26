package com.javaee.aiservice.review.m0.model;

public record DigestEntry(String stableId, String digest) {
    public DigestEntry {
        ModelChecks.required(stableId, "stableId", 64);
        ModelChecks.digest(digest, "digest");
    }
}
