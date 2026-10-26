package com.javaee.aiservice.review.m0.file;

import java.util.Locale;

public record FileArtifactSnapshot(
        String fileId,
        String originalFileName,
        String declaredContentType,
        long sizeBytes,
        String contentDigest,
        String storageVersion,
        boolean available
) {
    public FileArtifactSnapshot {
        require(fileId, "fileId", 64);
        require(originalFileName, "originalFileName", 1024);
        if (declaredContentType != null && declaredContentType.length() > 255) {
            throw new IllegalArgumentException("declaredContentType is too long");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative");
        }
        if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest must be lower-case SHA-256 hex");
        }
        if (storageVersion != null && (storageVersion.isBlank() || storageVersion.length() > 255)) {
            throw new IllegalArgumentException("storageVersion is invalid");
        }
        declaredContentType = declaredContentType == null
                ? null
                : declaredContentType.trim().toLowerCase(Locale.ROOT);
    }

    public boolean sameImmutableFacts(FileArtifactSnapshot other) {
        return other != null
                && fileId.equals(other.fileId)
                && originalFileName.equals(other.originalFileName)
                && sizeBytes == other.sizeBytes
                && contentDigest.equals(other.contentDigest)
                && java.util.Objects.equals(declaredContentType, other.declaredContentType)
                && java.util.Objects.equals(storageVersion, other.storageVersion)
                && available == other.available;
    }

    private static void require(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
