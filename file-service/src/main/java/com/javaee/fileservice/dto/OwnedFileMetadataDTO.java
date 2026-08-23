package com.javaee.fileservice.dto;

/** Public metadata for the owner-aware file boundary. Internal storage locators are omitted. */
public record OwnedFileMetadataDTO(
        String fileId,
        String originalFileName,
        String declaredContentType,
        long sizeBytes,
        String contentDigest,
        String storageVersion,
        String contentSignature,
        boolean available
) { }
