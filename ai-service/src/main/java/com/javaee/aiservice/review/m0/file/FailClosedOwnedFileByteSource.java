package com.javaee.aiservice.review.m0.file;

import java.io.InputStream;

/**
 * Production-safe boundary while OmniDoc lacks a fileId-level owner proof contract.
 */
public final class FailClosedOwnedFileByteSource implements OwnedFileByteSource {
    private static final String MESSAGE =
            "Owned file access is unavailable because the file service cannot prove fileId ownership";

    @Override
    public FileArtifactSnapshot getOwnedFile(String ownerId, String fileId) {
        throw new OwnedFileAccessException(OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE, MESSAGE);
    }

    @Override
    public InputStream openOwnedFile(String ownerId, String fileId, FileArtifactSnapshot expectedSnapshot) {
        throw new OwnedFileAccessException(OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE, MESSAGE);
    }
}
