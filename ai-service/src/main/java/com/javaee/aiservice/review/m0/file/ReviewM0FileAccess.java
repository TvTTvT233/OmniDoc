package com.javaee.aiservice.review.m0.file;

import java.io.InputStream;

public interface ReviewM0FileAccess {
    FileArtifactSnapshot getOwnedFile(String ownerId, String fileId);

    InputStream openFile(String ownerId, String fileId);

    InputStream openFile(
            String ownerId,
            String fileId,
            FileArtifactSnapshot expectedSnapshot
    );

    InputStream openZipEntry(
            String ownerId,
            String fileId,
            String normalizedEntryPath,
            String expectedEntryDigest
    );
}
