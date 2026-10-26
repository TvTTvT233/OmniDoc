package com.javaee.aiservice.review.m0.file;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryOwnedFileByteSource implements OwnedFileByteSource {
    private final Map<String, StoredFile> files = new LinkedHashMap<>();
    private int metadataReads;
    private int byteReads;

    public void put(
            String ownerId,
            String fileId,
            String originalFileName,
            String declaredContentType,
            byte[] bytes,
            String storageVersion,
            String objectKey
    ) {
        files.put(fileId, new StoredFile(
                ownerId, fileId, originalFileName, declaredContentType,
                Arrays.copyOf(bytes, bytes.length), storageVersion, objectKey, true));
    }

    public void setAvailable(String fileId, boolean available) {
        StoredFile current = requireFile(fileId);
        files.put(fileId, current.withAvailable(available));
    }

    public void replaceBytes(String fileId, byte[] bytes, String storageVersion) {
        StoredFile current = requireFile(fileId);
        files.put(fileId, current.withBytes(bytes, storageVersion));
    }

    public void replaceOriginalFileName(String fileId, String originalFileName) {
        StoredFile current = requireFile(fileId);
        files.put(fileId, current.withOriginalFileName(originalFileName));
    }

    public FileFacts facts(String fileId) {
        StoredFile file = requireFile(fileId);
        return new FileFacts(
                file.originalFileName, file.declaredContentType, file.storageVersion,
                file.objectKey, Arrays.copyOf(file.bytes, file.bytes.length),
                SyntheticFileFixtures.sha256(file.bytes), file.available);
    }

    public int metadataReads() {
        return metadataReads;
    }

    public int byteReads() {
        return byteReads;
    }

    @Override
    public FileArtifactSnapshot getOwnedFile(String ownerId, String fileId) {
        metadataReads++;
        StoredFile file = files.get(fileId);
        if (file == null || !file.ownerId.equals(ownerId)) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.NOT_FOUND,
                    "Owned file does not exist");
        }
        return snapshot(file);
    }

    @Override
    public InputStream openOwnedFile(
            String ownerId,
            String fileId,
            FileArtifactSnapshot expectedSnapshot
    ) {
        byteReads++;
        StoredFile file = files.get(fileId);
        if (file == null || !file.ownerId.equals(ownerId)) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.NOT_FOUND,
                    "Owned file does not exist");
        }
        FileArtifactSnapshot current = snapshot(file);
        if (!current.sameImmutableFacts(expectedSnapshot)) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.INTEGRITY_MISMATCH,
                    "Owned file snapshot changed before the byte read");
        }
        if (!file.available) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.SOURCE_UNAVAILABLE,
                    "Owned file is unavailable");
        }
        return new ByteArrayInputStream(Arrays.copyOf(file.bytes, file.bytes.length));
    }

    private StoredFile requireFile(String fileId) {
        StoredFile file = files.get(fileId);
        if (file == null) {
            throw new IllegalArgumentException("Unknown synthetic file");
        }
        return file;
    }

    private static FileArtifactSnapshot snapshot(StoredFile file) {
        return new FileArtifactSnapshot(
                file.fileId, file.originalFileName, file.declaredContentType,
                file.bytes.length, SyntheticFileFixtures.sha256(file.bytes),
                file.storageVersion, file.available);
    }

    public record FileFacts(
            String originalFileName,
            String declaredContentType,
            String storageVersion,
            String objectKey,
            byte[] bytes,
            String contentDigest,
            boolean available
    ) {
        public FileFacts {
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FileFacts facts)) {
                return false;
            }
            return available == facts.available
                    && java.util.Objects.equals(originalFileName, facts.originalFileName)
                    && java.util.Objects.equals(declaredContentType, facts.declaredContentType)
                    && java.util.Objects.equals(storageVersion, facts.storageVersion)
                    && java.util.Objects.equals(objectKey, facts.objectKey)
                    && Arrays.equals(bytes, facts.bytes)
                    && java.util.Objects.equals(contentDigest, facts.contentDigest);
        }

        @Override
        public int hashCode() {
            int result = java.util.Objects.hash(
                    originalFileName, declaredContentType, storageVersion,
                    objectKey, contentDigest, available);
            return 31 * result + Arrays.hashCode(bytes);
        }
    }

    private record StoredFile(
            String ownerId,
            String fileId,
            String originalFileName,
            String declaredContentType,
            byte[] bytes,
            String storageVersion,
            String objectKey,
            boolean available
    ) {
        private StoredFile withAvailable(boolean value) {
            return new StoredFile(ownerId, fileId, originalFileName, declaredContentType,
                    bytes, storageVersion, objectKey, value);
        }

        private StoredFile withBytes(byte[] value, String version) {
            return new StoredFile(ownerId, fileId, originalFileName, declaredContentType,
                    Arrays.copyOf(value, value.length), version, objectKey, available);
        }

        private StoredFile withOriginalFileName(String value) {
            return new StoredFile(ownerId, fileId, value, declaredContentType,
                    bytes, storageVersion, objectKey, available);
        }
    }
}
