package com.javaee.aiservice.review.m0.file;

import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.archive.ZipArchiveEntryOpener;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedReviewM0FileAccessTest {
    private final ArchiveSafetyLimits limits = ArchiveSafetyLimits.secureDefaults();

    @Test
    void failClosedBoundaryNeverUsesPermitAllOrHeaderFallback() {
        VerifiedReviewM0FileAccess access = VerifiedReviewM0FileAccess.failClosed(
                new ZipArchiveEntryOpener(limits));
        OwnedFileAccessException metadata = assertThrows(OwnedFileAccessException.class,
                () -> access.getOwnedFile("owner", "file"));
        OwnedFileAccessException bytes = assertThrows(OwnedFileAccessException.class,
                () -> access.openFile("owner", "file"));
        assertEquals(ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE, metadata.errorCode());
        assertEquals(ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE, bytes.errorCode());
    }

    @Test
    void crossOwnerMetadataAndBytesAreBothHiddenAsNotFound() {
        InMemoryOwnedFileByteSource source = new InMemoryOwnedFileByteSource();
        byte[] bytes = SyntheticFileFixtures.pdf("owned");
        source.put("owner-a", "file-a", "form.pdf", "application/pdf", bytes, "v1", "private/key");
        VerifiedReviewM0FileAccess access = access(source);

        OwnedFileAccessException metadata = assertThrows(OwnedFileAccessException.class,
                () -> access.getOwnedFile("owner-b", "file-a"));
        OwnedFileAccessException stream = assertThrows(OwnedFileAccessException.class,
                () -> access.openFile("owner-b", "file-a"));
        assertEquals(OwnedFileAccessException.Kind.NOT_FOUND, metadata.kind());
        assertEquals(OwnedFileAccessException.Kind.NOT_FOUND, stream.kind());
    }

    @Test
    void stableSnapshotBindsOriginalNameSizeSha256TypeAndVersion() throws Exception {
        InMemoryOwnedFileByteSource source = new InMemoryOwnedFileByteSource();
        byte[] bytes = SyntheticFileFixtures.pdf("snapshot");
        source.put("owner", "file", "原件.pdf", "application/pdf", bytes, "version-7", "opaque/key");
        VerifiedReviewM0FileAccess access = access(source);

        FileArtifactSnapshot snapshot = access.getOwnedFile("owner", "file");
        try (InputStream input = access.openFile("owner", "file")) {
            assertArrayEquals(bytes, input.readAllBytes());
        }
        assertEquals("file", snapshot.fileId());
        assertEquals("原件.pdf", snapshot.originalFileName());
        assertEquals(bytes.length, snapshot.sizeBytes());
        assertEquals(SyntheticFileFixtures.sha256(bytes), snapshot.contentDigest());
        assertEquals("application/pdf", snapshot.declaredContentType());
        assertEquals("version-7", snapshot.storageVersion());
    }

    @Test
    void metadataDriftDuringReadFailsIntegrityCheck() throws Exception {
        InMemoryOwnedFileByteSource source = new InMemoryOwnedFileByteSource();
        source.put("owner", "file", "form.pdf", "application/pdf",
                SyntheticFileFixtures.pdf("before"), "v1", "opaque/key");
        VerifiedReviewM0FileAccess access = access(source);

        try (InputStream input = access.openFile("owner", "file")) {
            source.replaceOriginalFileName("file", "changed.pdf");
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    @Test
    void zipEntryPortReturnsOnlyTheDigestBoundTarget() throws Exception {
        InMemoryOwnedFileByteSource source = new InMemoryOwnedFileByteSource();
        byte[] first = SyntheticFileFixtures.pdf("first");
        byte[] target = SyntheticFileFixtures.png((byte) 9);
        byte[] zip = SyntheticFileFixtures.zip(List.of(
                SyntheticFileFixtures.entry("first.pdf", first),
                SyntheticFileFixtures.entry("target.png", target)));
        source.put("owner", "zip", "bundle.zip", "application/zip", zip, "v1", "opaque/zip");
        VerifiedReviewM0FileAccess access = access(source);

        try (InputStream input = access.openZipEntry(
                "owner", "zip", "target.png", SyntheticFileFixtures.sha256(target))) {
            assertArrayEquals(target, input.readAllBytes());
        }
    }

    private VerifiedReviewM0FileAccess access(InMemoryOwnedFileByteSource source) {
        return new VerifiedReviewM0FileAccess(source, new ZipArchiveEntryOpener(limits));
    }
}
