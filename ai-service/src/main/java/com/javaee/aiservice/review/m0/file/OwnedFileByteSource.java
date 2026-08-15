package com.javaee.aiservice.review.m0.file;

import java.io.InputStream;

/**
 * Security-critical primitive implemented only by a file service integration that can prove
 * the authenticated owner owns the requested fileId. Implementations must hide missing and
 * cross-owner files identically and must bind streams to the returned immutable snapshot.
 */
public interface OwnedFileByteSource {
    FileArtifactSnapshot getOwnedFile(String ownerId, String fileId);

    InputStream openOwnedFile(String ownerId, String fileId, FileArtifactSnapshot expectedSnapshot);
}
