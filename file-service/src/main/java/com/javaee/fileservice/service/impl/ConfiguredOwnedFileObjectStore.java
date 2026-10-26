package com.javaee.fileservice.service.impl;

import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.exception.OwnedFileNotFoundException;
import com.javaee.fileservice.exception.OwnedFileUnavailableException;
import com.javaee.fileservice.service.OwnedFileObjectStore;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class ConfiguredOwnedFileObjectStore implements OwnedFileObjectStore {
    private final FileStorageConfig config;
    private final MinioClient minio;

    public ConfiguredOwnedFileObjectStore(FileStorageConfig config, MinioClient minio) {
        this.config = config;
        this.minio = minio;
    }

    @Override
    public StoredObject store(
            String objectKey,
            InputStream source,
            long expectedSize,
            String declaredContentType
    ) {
        requireSafeObjectKey(objectKey);
        DigestCountingInputStream input = new DigestCountingInputStream(source);
        try {
            if ("local".equals(config.getStorageType())) {
                Path target = localTarget(objectKey);
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(
                        target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    input.transferTo(output);
                }
            } else if ("minio".equals(config.getStorageType())) {
                ensureBucketExists();
                minio.putObject(PutObjectArgs.builder()
                        .bucket(config.getConfiguredBucketName())
                        .object(objectKey)
                        .stream(input, expectedSize, -1)
                        .contentType(declaredContentType)
                        .build());
            } else {
                throw new OwnedFileUnavailableException("Unsupported owned file storage type");
            }
            if (input.count() != expectedSize) {
                deleteByLocation(config.getStorageType(), config.getConfiguredBucketName(), objectKey);
                throw new OwnedFileUnavailableException("Stored byte count does not match upload metadata");
            }
            String digest = input.hexDigest();
            return new StoredObject(input.count(), digest, "sha256:" + digest);
        } catch (OwnedFileUnavailableException error) {
            throw error;
        } catch (Exception error) {
            deleteByLocation(config.getStorageType(), config.getConfiguredBucketName(), objectKey);
            throw new OwnedFileUnavailableException("Owned file storage is unavailable", error);
        }
    }

    @Override
    public InputStream open(FileMetadata metadata) {
        requireSafeObjectKey(metadata.getObjectKey());
        try {
            if ("local".equals(metadata.getStorageType())) {
                return Files.newInputStream(localTarget(metadata.getObjectKey()), StandardOpenOption.READ);
            }
            if ("minio".equals(metadata.getStorageType())) {
                return minio.getObject(GetObjectArgs.builder()
                        .bucket(metadata.getBucketName())
                        .object(metadata.getObjectKey())
                        .build());
            }
            throw new OwnedFileUnavailableException("Unsupported owned file storage type");
        } catch (NoSuchFileException error) {
            throw new OwnedFileNotFoundException(error);
        } catch (ErrorResponseException error) {
            String code = error.errorResponse() == null ? null : error.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                throw new OwnedFileNotFoundException(error);
            }
            throw new OwnedFileUnavailableException("Owned file storage is unavailable", error);
        } catch (OwnedFileNotFoundException | OwnedFileUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw new OwnedFileUnavailableException("Owned file storage is unavailable", error);
        }
    }

    @Override
    public void delete(FileMetadata metadata) {
        deleteByLocation(metadata.getStorageType(), metadata.getBucketName(), metadata.getObjectKey());
    }

    private void deleteByLocation(String storageType, String bucketName, String objectKey) {
        try {
            if ("local".equals(storageType)) {
                Files.deleteIfExists(localTarget(objectKey));
            } else if ("minio".equals(storageType)) {
                minio.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucketName).object(objectKey).build());
            }
        } catch (Exception ignored) {
            // Best-effort compensation. The failed metadata insert never makes this object readable.
        }
    }

    private Path localTarget(String objectKey) {
        Path root = Path.of(config.getLocalPath()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new OwnedFileUnavailableException("Owned file storage locator is invalid");
        }
        return target;
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minio.bucketExists(
                BucketExistsArgs.builder().bucket(config.getConfiguredBucketName()).build());
        if (!exists) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(config.getConfiguredBucketName()).build());
        }
    }

    private static void requireSafeObjectKey(String objectKey) {
        if (objectKey == null
                || !objectKey.matches("[0-9a-fA-F-]{36}(?:\\.[A-Za-z0-9]{1,16})?")) {
            throw new OwnedFileUnavailableException("Owned file storage locator is invalid");
        }
    }

    private static final class DigestCountingInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long count;
        private boolean finished;

        private DigestCountingInputStream(InputStream input) {
            super(input);
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                digest.update((byte) value);
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                digest.update(bytes, offset, read);
                count += read;
            }
            return read;
        }

        long count() {
            return count;
        }

        String hexDigest() {
            if (finished) {
                throw new IllegalStateException("Digest was already consumed");
            }
            finished = true;
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
