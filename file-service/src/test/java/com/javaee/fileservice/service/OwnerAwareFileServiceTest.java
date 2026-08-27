package com.javaee.fileservice.service;

import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.config.OwnedFileDownloadProperties;
import com.javaee.fileservice.dto.OwnedFileMetadataDTO;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.exception.OwnedFileNotFoundException;
import com.javaee.fileservice.exception.OwnedFileUnavailableException;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.service.impl.OwnerAwareFileServiceImpl;
import com.javaee.fileservice.service.impl.FileMetadataServiceImpl;
import com.javaee.fileservice.service.impl.OwnedFileDownloadStaging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnerAwareFileServiceTest {
    @TempDir Path temporaryDirectory;
    private FileMetadataMapper mapper;
    private InMemoryObjectStore objects;
    private FileStorageConfig storage;
    private AtomicReference<FileMetadata> inserted;
    private OwnedFileDownloadStaging staging;
    private OwnerAwareFileService service;

    @BeforeEach
    void setUp() {
        mapper = mock(FileMetadataMapper.class);
        objects = new InMemoryObjectStore();
        storage = storage();
        inserted = new AtomicReference<>();
        OwnedFileDownloadProperties properties = new OwnedFileDownloadProperties();
        properties.setStagingDirectory(temporaryDirectory.resolve("staging"));
        staging = new OwnedFileDownloadStaging(properties);
        ReflectionTestUtils.invokeMethod(staging, "initialize");
        service = service();
    }

    @AfterEach
    void tearDown() {
        staging.close();
    }

    @Test
    void authenticatedUploadBindsOwnerAndStableMetadataToExactBytes() throws Exception {
        byte[] bytes = "%PDF-1.7\nowned".getBytes(StandardCharsets.UTF_8);
        OwnedFileMetadataDTO uploaded = service.upload("42", new MockMultipartFile(
                "file", "原始申请表.pdf", "application/pdf", bytes));

        assertThat(inserted.get().getOwnerId()).isEqualTo("42");
        assertThat(uploaded.originalFileName()).isEqualTo("原始申请表.pdf");
        assertThat(uploaded.sizeBytes()).isEqualTo(bytes.length);
        assertThat(uploaded.contentDigest()).isEqualTo(sha256(bytes));
        assertThat(uploaded.declaredContentType()).isEqualTo("application/pdf");
        assertThat(uploaded.contentSignature()).isEqualTo("PDF");
        assertThat(uploaded.storageVersion()).isEqualTo("sha256:" + sha256(bytes));
        try (InputStream input = service.open("42", uploaded.fileId()).input()) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void ownerNullHistoricalRowsCrossOwnerAndMissingAllFailClosed() {
        byte[] bytes = "%PDF-1.7\nowned".getBytes(StandardCharsets.UTF_8);
        OwnedFileMetadataDTO uploaded = service.upload("42", new MockMultipartFile(
                "file", "owned.pdf", "application/pdf", bytes));

        assertThatThrownBy(() -> service.getMetadata("43", uploaded.fileId()))
                .isInstanceOf(OwnedFileNotFoundException.class)
                .hasMessage("Owned file does not exist");
        assertThatThrownBy(() -> service.getMetadata("42", "missing"))
                .isInstanceOf(OwnedFileNotFoundException.class)
                .hasMessage("Owned file does not exist");

        FileMetadata historical = new FileMetadata();
        historical.setFileId("legacy");
        historical.setOwnerId(null);
        when(mapper.selectOwnedActiveByFileId("42", "legacy")).thenReturn(null);
        assertThatThrownBy(() -> service.getMetadata("42", "legacy"))
                .isInstanceOf(OwnedFileNotFoundException.class)
                .hasMessage("Owned file does not exist");
    }

    @Test
    void corruptedStoredBytesCannotCompleteAnOwnedDownload() {
        byte[] bytes = "%PDF-1.7\nowned".getBytes(StandardCharsets.UTF_8);
        OwnedFileMetadataDTO uploaded = service.upload("42", new MockMultipartFile(
                "file", "owned.pdf", "application/pdf", bytes));
        objects.replace(inserted.get().getObjectKey(), "changed".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.open("42", uploaded.fileId()))
                .isInstanceOf(OwnedFileUnavailableException.class)
                .hasMessageContaining("integrity verification");
    }

    @Test
    void incompleteOwnedMetadataIsAServiceIntegrityFailure() {
        FileMetadata corrupt = active("42", "file-corrupt");
        corrupt.setSha256(null);
        when(mapper.selectOwnedActiveByFileId("42", "file-corrupt")).thenReturn(corrupt);

        assertThatThrownBy(() -> service.getMetadata("42", "file-corrupt"))
                .isInstanceOf(OwnedFileUnavailableException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void migrationKeepsHistoricalOwnerNullableAndConstrainsOwnedRows() throws Exception {
        Path working = Path.of("").toAbsolutePath().normalize();
        Path root = Files.isDirectory(working.resolve("file-service"))
                ? working : working.getParent();
        String migration = Files.readString(root.resolve(
                "file-service/src/main/resources/db/file-service/V002__owner_aware_file_access.sql"));
        String schema = Files.readString(root.resolve("file-service/src/main/resources/file.sql"));

        assertThat(migration)
                .contains("owner_id VARCHAR(128) NULL")
                .doesNotContain("UPDATE file_metadata")
                .contains("idx_file_metadata_owner_file_status")
                .contains("chk_file_metadata_owned_integrity")
                .contains("owner_id IS NULL OR")
                .contains("sha256 REGEXP '^[0-9a-f]{64}$'");
        assertThat(schema)
                .contains("`owner_id` VARCHAR(128) DEFAULT NULL")
                .contains("`sha256` CHAR(64) DEFAULT NULL")
                .contains("`storage_version` VARCHAR(255) DEFAULT NULL");
    }

    @Test
    void legacyPermitAllMetadataBoundaryCannotSeeNewOwnedRows() {
        FileMetadata owned = active("42", "owned-file");
        FileMetadata legacy = active(null, "legacy-file");
        when(mapper.selectByFileId("owned-file")).thenReturn(owned);
        when(mapper.selectByFileId("legacy-file")).thenReturn(legacy);
        FileMetadataServiceImpl legacyService = new FileMetadataServiceImpl();
        ReflectionTestUtils.setField(legacyService, "fileMetadataMapper", mapper);
        ReflectionTestUtils.setField(legacyService, "fileStorageConfig", storage);

        assertThat(legacyService.getMetadata("owned-file")).isNull();
        assertThat(legacyService.getMetadata("legacy-file")).isSameAs(legacy);
    }

    private OwnerAwareFileService service() {
        when(mapper.insert(any(FileMetadata.class))).thenAnswer(invocation -> {
            FileMetadata value = invocation.getArgument(0);
            inserted.set(value);
            return 1;
        });
        when(mapper.selectOwnedActiveByFileId(any(), any())).thenAnswer(invocation -> {
            FileMetadata value = inserted.get();
            if (value == null) return null;
            String owner = invocation.getArgument(0);
            String fileId = invocation.getArgument(1);
            return owner.equals(value.getOwnerId()) && fileId.equals(value.getFileId()) ? value : null;
        });
        return new OwnerAwareFileServiceImpl(mapper, objects, storage, staging);
    }

    private static FileStorageConfig storage() {
        FileStorageConfig config = new FileStorageConfig();
        ReflectionTestUtils.setField(config, "storageType", "local");
        ReflectionTestUtils.setField(config, "localPath", "owned-test");
        ReflectionTestUtils.setField(config, "bucketName", "not-used");
        return config;
    }

    private static FileMetadata active(String owner, String fileId) {
        FileMetadata value = new FileMetadata();
        value.setOwnerId(owner);
        value.setFileId(fileId);
        value.setStatus("ACTIVE");
        value.setOriginalFileName("file.pdf");
        value.setFileType("application/pdf");
        value.setFileSize(1);
        value.setSha256("0".repeat(64));
        value.setStorageVersion("sha256:" + "0".repeat(64));
        value.setContentSignature("PDF");
        value.setStorageType("local");
        value.setObjectKey("00000000-0000-0000-0000-000000000000.pdf");
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static final class InMemoryObjectStore implements OwnedFileObjectStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public StoredObject store(String objectKey, InputStream input, long expectedSize, String contentType) {
            try {
                byte[] bytes = input.readAllBytes();
                if (bytes.length != expectedSize) throw new IllegalStateException("size mismatch");
                values.put(objectKey, bytes);
                String digest = sha256(bytes);
                return new StoredObject(bytes.length, digest, "sha256:" + digest);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }

        @Override
        public InputStream open(FileMetadata metadata) {
            return new ByteArrayInputStream(values.get(metadata.getObjectKey()));
        }

        @Override
        public void delete(FileMetadata metadata) {
            values.remove(metadata.getObjectKey());
        }

        void replace(String objectKey, byte[] bytes) {
            values.put(objectKey, bytes);
        }
    }
}
