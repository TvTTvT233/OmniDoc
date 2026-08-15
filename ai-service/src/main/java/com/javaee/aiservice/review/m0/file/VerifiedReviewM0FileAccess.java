package com.javaee.aiservice.review.m0.file;

import com.javaee.aiservice.review.m0.archive.ArchiveInspectionException;
import com.javaee.aiservice.review.m0.archive.ZipArchiveEntryOpener;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class VerifiedReviewM0FileAccess implements ReviewM0FileAccess {
    private final OwnedFileByteSource byteSource;
    private final ZipArchiveEntryOpener zipEntryOpener;

    public VerifiedReviewM0FileAccess(
            OwnedFileByteSource byteSource,
            ZipArchiveEntryOpener zipEntryOpener
    ) {
        this.byteSource = java.util.Objects.requireNonNull(byteSource);
        this.zipEntryOpener = java.util.Objects.requireNonNull(zipEntryOpener);
    }

    @Override
    public FileArtifactSnapshot getOwnedFile(String ownerId, String fileId) {
        requireIdentity(ownerId, fileId);
        FileArtifactSnapshot snapshot = byteSource.getOwnedFile(ownerId, fileId);
        if (snapshot == null || !snapshot.fileId().equals(fileId)) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.INTEGRITY_MISMATCH,
                    "Owned file snapshot does not match the requested fileId");
        }
        return snapshot;
    }

    @Override
    public InputStream openFile(String ownerId, String fileId) {
        FileArtifactSnapshot before = getOwnedFile(ownerId, fileId);
        if (!before.available()) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.SOURCE_UNAVAILABLE,
                    "Owned source file is unavailable");
        }
        InputStream stream = byteSource.openOwnedFile(ownerId, fileId, before);
        if (stream == null) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE,
                    "Owned file byte source returned no stream");
        }
        return new IntegrityVerifyingInputStream(stream, before, () -> {
            FileArtifactSnapshot after = getOwnedFile(ownerId, fileId);
            if (!before.sameImmutableFacts(after)) {
                throw new OwnedFileAccessException(
                        OwnedFileAccessException.Kind.INTEGRITY_MISMATCH,
                        "Owned file facts changed during the read");
            }
        });
    }

    @Override
    public InputStream openZipEntry(
            String ownerId,
            String fileId,
            String normalizedEntryPath,
            String expectedEntryDigest
    ) {
        InputStream archive = openFile(ownerId, fileId);
        try {
            return zipEntryOpener.open(archive, normalizedEntryPath, expectedEntryDigest);
        } catch (ArchiveInspectionException error) {
            try {
                archive.close();
            } catch (IOException ignored) {
                // preserve the archive failure
            }
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.INTEGRITY_MISMATCH,
                    "ZIP entry cannot be opened from the owned file snapshot",
                    error);
        }
    }

    public static VerifiedReviewM0FileAccess failClosed(ZipArchiveEntryOpener zipEntryOpener) {
        return new VerifiedReviewM0FileAccess(new FailClosedOwnedFileByteSource(), zipEntryOpener);
    }

    private static void requireIdentity(String ownerId, String fileId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 128
                || fileId == null || fileId.isBlank() || fileId.length() > 64) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.NOT_FOUND,
                    "Owned file does not exist");
        }
    }

    @FunctionalInterface
    private interface VerificationCallback {
        void verify();
    }

    private static final class IntegrityVerifyingInputStream extends InputStream {
        private final InputStream delegate;
        private final FileArtifactSnapshot expected;
        private final VerificationCallback callback;
        private final MessageDigest digest;
        private long count;
        private boolean verified;

        private IntegrityVerifyingInputStream(
                InputStream delegate,
                FileArtifactSnapshot expected,
                VerificationCallback callback
        ) {
            this.delegate = delegate;
            this.expected = expected;
            this.callback = callback;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                digest.update(bytes, offset, read);
                count += read;
            } else if (read == -1) {
                verifyAtEnd();
            }
            return read;
        }

        private void verifyAtEnd() throws IOException {
            if (verified) {
                return;
            }
            verified = true;
            String actualDigest = HexFormat.of().formatHex(digest.digest());
            if (count != expected.sizeBytes() || !actualDigest.equals(expected.contentDigest())) {
                throw new IOException("Owned file bytes do not match the immutable snapshot",
                        new OwnedFileAccessException(
                                OwnedFileAccessException.Kind.INTEGRITY_MISMATCH,
                                "Owned file bytes do not match the immutable snapshot"));
            }
            try {
                callback.verify();
            } catch (OwnedFileAccessException error) {
                throw new IOException(error.getMessage(), error);
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
