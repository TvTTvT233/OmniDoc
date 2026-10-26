package com.javaee.aiservice.review.m0.archive;

import com.javaee.aiservice.review.m0.model.FormalFileType;

public record ArchiveEntryArtifact(
        int entryIndex,
        String normalizedEntryPath,
        String originalEntryName,
        String leafFileName,
        FormalFileType formalFileType,
        long uncompressedSizeBytes,
        String contentDigest
) {
}
