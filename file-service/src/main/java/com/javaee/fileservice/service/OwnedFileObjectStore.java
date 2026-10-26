package com.javaee.fileservice.service;

import com.javaee.fileservice.entity.FileMetadata;

import java.io.InputStream;

/** Internal byte store. Authorization is completed against metadata before this port is called. */
public interface OwnedFileObjectStore {
    StoredObject store(
            String objectKey,
            InputStream input,
            long expectedSize,
            String declaredContentType);

    InputStream open(FileMetadata metadata);

    void delete(FileMetadata metadata);

    record StoredObject(long sizeBytes, String sha256, String storageVersion) { }
}
