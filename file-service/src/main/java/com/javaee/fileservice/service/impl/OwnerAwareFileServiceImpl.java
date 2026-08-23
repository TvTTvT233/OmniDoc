package com.javaee.fileservice.service.impl;

import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.dto.OwnedFileMetadataDTO;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.exception.OwnedFileNotFoundException;
import com.javaee.fileservice.exception.OwnedFileUnavailableException;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.service.OwnedFileObjectStore;
import com.javaee.fileservice.service.OwnerAwareFileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class OwnerAwareFileServiceImpl implements OwnerAwareFileService {
    private final FileMetadataMapper metadataMapper;
    private final OwnedFileObjectStore objectStore;
    private final FileStorageConfig storageConfig;

    public OwnerAwareFileServiceImpl(
            FileMetadataMapper metadataMapper,
            OwnedFileObjectStore objectStore,
            FileStorageConfig storageConfig
    ) {
        this.metadataMapper = metadataMapper;
        this.objectStore = objectStore;
        this.storageConfig = storageConfig;
    }

    @Override
    public OwnedFileMetadataDTO upload(String ownerId, MultipartFile file) {
        requireOwner(ownerId);
        if (file == null) {
            throw new IllegalArgumentException("file is required");
        }
        String originalFileName = safeOriginalFileName(file.getOriginalFilename());
        String declaredContentType = safeContentType(file.getContentType());
        String signature = detectSignature(file);
        String fileId = UUID.randomUUID().toString();
        String objectKey = fileId + safeExtension(originalFileName);

        OwnedFileObjectStore.StoredObject stored;
        try (InputStream input = file.getInputStream()) {
            stored = objectStore.store(objectKey, input, file.getSize(), declaredContentType);
        } catch (OwnedFileUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw new OwnedFileUnavailableException("Owned file upload is unavailable", error);
        }

        FileMetadata metadata = new FileMetadata();
        metadata.setId(UUID.randomUUID().toString());
        metadata.setFileId(fileId);
        metadata.setFileName(originalFileName);
        metadata.setOriginalFileName(originalFileName);
        metadata.setFilePath(storageConfig.getLocalPath());
        metadata.setFileType(declaredContentType);
        metadata.setFileSize(stored.sizeBytes());
        metadata.setOwnerId(ownerId);
        metadata.setSha256(stored.sha256());
        metadata.setStorageVersion(stored.storageVersion());
        metadata.setContentSignature(signature);
        metadata.setStorageType(storageConfig.getStorageType());
        metadata.setBucketName(storageConfig.getConfiguredBucketName());
        metadata.setObjectKey(objectKey);
        metadata.setStatus("ACTIVE");
        metadata.setCreateBy(ownerId);
        metadata.setCreateTime(LocalDateTime.now());
        metadata.setUpdateTime(LocalDateTime.now());
        try {
            if (metadataMapper.insert(metadata) != 1) {
                throw new IllegalStateException("Owned file metadata was not inserted");
            }
        } catch (Exception error) {
            objectStore.delete(metadata);
            throw new OwnedFileUnavailableException("Owned file metadata is unavailable", error);
        }
        return snapshot(metadata);
    }

    @Override
    public OwnedFileMetadataDTO getMetadata(String ownerId, String fileId) {
        return snapshot(requireOwned(ownerId, fileId));
    }

    @Override
    public OwnedFileStream open(String ownerId, String fileId) {
        FileMetadata metadata = requireOwned(ownerId, fileId);
        InputStream input = objectStore.open(metadata);
        if (input == null) {
            throw new OwnedFileUnavailableException("Owned file storage returned no stream");
        }
        return new OwnedFileStream(snapshot(metadata), new VerifyingInputStream(
                input, metadata.getFileSize(), metadata.getSha256()));
    }

    private FileMetadata requireOwned(String ownerId, String fileId) {
        requireOwner(ownerId);
        if (fileId == null || fileId.isBlank() || fileId.length() > 64) {
            throw new OwnedFileNotFoundException();
        }
        final FileMetadata metadata;
        try {
            metadata = metadataMapper.selectOwnedActiveByFileId(ownerId, fileId);
        } catch (Exception error) {
            throw new OwnedFileUnavailableException("Owned file metadata is unavailable", error);
        }
        if (metadata == null) {
            throw new OwnedFileNotFoundException();
        }
        if (!ownerId.equals(metadata.getOwnerId())
                || !fileId.equals(metadata.getFileId())
                || !"ACTIVE".equals(metadata.getStatus())) {
            throw new OwnedFileNotFoundException();
        }
        validateImmutableFacts(metadata);
        return metadata;
    }

    private static OwnedFileMetadataDTO snapshot(FileMetadata metadata) {
        validateImmutableFacts(metadata);
        return new OwnedFileMetadataDTO(
                metadata.getFileId(), metadata.getOriginalFileName(), metadata.getFileType(),
                metadata.getFileSize(), metadata.getSha256(), metadata.getStorageVersion(),
                metadata.getContentSignature(), true);
    }

    private static void validateImmutableFacts(FileMetadata metadata) {
        if (metadata.getOriginalFileName() == null || metadata.getOriginalFileName().isBlank()
                || metadata.getOriginalFileName().length() > 255
                || metadata.getFileSize() < 0
                || metadata.getSha256() == null
                || !metadata.getSha256().matches("[0-9a-f]{64}")
                || metadata.getStorageVersion() == null || metadata.getStorageVersion().isBlank()
                || metadata.getStorageVersion().length() > 255
                || metadata.getContentSignature() == null || metadata.getContentSignature().isBlank()
                || metadata.getFileType() == null || metadata.getFileType().isBlank()
                || metadata.getFileType().length() > 128) {
            throw new OwnedFileUnavailableException("Owned file metadata integrity cannot be verified");
        }
    }

    private static void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 128) {
            throw new SecurityException("Authentication is required");
        }
    }

    private static String safeOriginalFileName(String rawName) {
        if (rawName == null) {
            throw new IllegalArgumentException("original file name is required");
        }
        String normalized = rawName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(value -> value == 0 || value < 32 || value == 127)) {
            throw new IllegalArgumentException("original file name is invalid");
        }
        return name;
    }

    private static String safeContentType(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            return "application/octet-stream";
        }
        String contentType = rawContentType.trim().toLowerCase(Locale.ROOT);
        if (contentType.length() > 128 || contentType.indexOf('\r') >= 0 || contentType.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("content type is invalid");
        }
        return contentType;
    }

    private static String safeExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot + 1);
        return extension.matches("[A-Za-z0-9]{1,16}")
                ? "." + extension.toLowerCase(Locale.ROOT)
                : "";
    }

    private static String detectSignature(MultipartFile file) {
        byte[] prefix = new byte[8];
        int length = 0;
        try (InputStream input = file.getInputStream()) {
            while (length < prefix.length) {
                int read = input.read(prefix, length, prefix.length - length);
                if (read == -1) break;
                length += read;
            }
        } catch (IOException error) {
            throw new OwnedFileUnavailableException("Owned file signature cannot be read", error);
        }
        if (length >= 5 && prefix[0] == '%' && prefix[1] == 'P' && prefix[2] == 'D'
                && prefix[3] == 'F' && prefix[4] == '-') return "PDF";
        if (length >= 4 && prefix[0] == 'P' && prefix[1] == 'K'
                && ((prefix[2] == 3 && prefix[3] == 4)
                || (prefix[2] == 5 && prefix[3] == 6)
                || (prefix[2] == 7 && prefix[3] == 8))) return "ZIP_CONTAINER";
        if (length >= 3 && (prefix[0] & 0xff) == 0xff && (prefix[1] & 0xff) == 0xd8
                && (prefix[2] & 0xff) == 0xff) return "JPEG";
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        if (length == png.length && java.util.Arrays.equals(prefix, png)) return "PNG";
        return "UNKNOWN";
    }

    private static final class VerifyingInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private final long expectedSize;
        private final String expectedDigest;
        private long count;
        private boolean verified;

        private VerifyingInputStream(InputStream input, long expectedSize, String expectedDigest) {
            super(input);
            this.expectedSize = expectedSize;
            this.expectedDigest = expectedDigest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value == -1) verify();
            else {
                digest.update((byte) value);
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read == -1) verify();
            else if (read > 0) {
                digest.update(bytes, offset, read);
                count += read;
            }
            return read;
        }

        private void verify() throws IOException {
            if (verified) return;
            verified = true;
            String actual = HexFormat.of().formatHex(digest.digest());
            if (count != expectedSize || !actual.equals(expectedDigest)) {
                throw new IOException("Owned file bytes failed integrity verification");
            }
        }
    }
}
