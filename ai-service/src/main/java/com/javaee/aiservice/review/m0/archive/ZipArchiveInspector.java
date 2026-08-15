package com.javaee.aiservice.review.m0.archive;

import com.javaee.aiservice.review.m0.file.ContentInspection;
import com.javaee.aiservice.review.m0.file.ContentInspectionException;
import com.javaee.aiservice.review.m0.file.FormalContentInspector;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FormalFileType;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public final class ZipArchiveInspector {
    private final ArchiveSafetyLimits limits;
    private final FormalContentInspector contentInspector;
    private final LongSupplier nanoClock;

    public ZipArchiveInspector(ArchiveSafetyLimits limits, FormalContentInspector contentInspector) {
        this(limits, contentInspector, System::nanoTime);
    }

    public ZipArchiveInspector(
            ArchiveSafetyLimits limits,
            FormalContentInspector contentInspector,
            LongSupplier nanoClock
    ) {
        this.limits = java.util.Objects.requireNonNull(limits);
        this.contentInspector = java.util.Objects.requireNonNull(contentInspector);
        this.nanoClock = java.util.Objects.requireNonNull(nanoClock);
    }

    public ArchiveInspection inspect(InputStream input) throws ArchiveInspectionException {
        java.util.Objects.requireNonNull(input, "input");
        ArchiveDeadline deadline = new ArchiveDeadline(limits, nanoClock);
        CountingDigestInputStream raw = new CountingDigestInputStream(input);
        PushbackInputStream pushed = new PushbackInputStream(raw, 4);
        requireZipSignature(pushed);

        ZipInputStream zip = new ZipInputStream(pushed, StandardCharsets.UTF_8);
        List<ArchiveEntryArtifact> artifacts = new ArrayList<>();
        Set<String> normalizedPaths = new HashSet<>();
        long[] totalExpanded = new long[]{0};
        long totalCompressed = 0;
        int entryIndex = 0;

        try {
            while (true) {
                deadline.check(entryIndex == 0 ? null : entryIndex, null);
                ZipEntry entry;
                try {
                    entry = zip.getNextEntry();
                } catch (ZipException error) {
                    throw zipFailure(error, entryIndex + 1, null);
                }
                if (entry == null) {
                    break;
                }
                entryIndex++;
                if (entryIndex > limits.maximumEntries()) {
                    throw limit("ARCHIVE_ENTRY_COUNT", entryIndex, ArchivePath.digestName(entry.getName()),
                            "Archive contains too many entries");
                }
                ArchivePath.ValidatedPath path = ArchivePath.validate(
                        entry.getName(), entry.isDirectory(), entryIndex, limits);
                if (!normalizedPaths.add(path.normalizedPath())) {
                    throw new ArchiveInspectionException(
                            CannotConfirmReason.CORRUPTED,
                            "ARCHIVE_DUPLICATE_NFC_PATH",
                            entryIndex,
                            path.nameDigest(),
                            "Archive contains duplicate Unicode-normalized paths");
                }
                validateMethod(entry, entryIndex, path.nameDigest());
                if (entry.getSize() > limits.maximumEntryUncompressedBytes()) {
                    throw limit("ARCHIVE_ENTRY_SIZE", entryIndex, path.nameDigest(),
                            "Archive entry expands beyond the operational limit");
                }

                BoundedEntryInputStream bounded = new BoundedEntryInputStream(
                        zip, limits, deadline, totalExpanded, entryIndex, path.nameDigest());
                if (entry.isDirectory()) {
                    drain(bounded);
                } else {
                    FormalFileType type = FormalFileType.fromFileName(path.leafName()).orElse(null);
                    if (type == null) {
                        throw new ArchiveInspectionException(
                                CannotConfirmReason.ARCHIVE_ENTRY_UNSUPPORTED,
                                "ARCHIVE_ENTRY_TYPE",
                                entryIndex,
                                path.nameDigest(),
                                "Archive leaf type is not supported");
                    }
                    if (type == FormalFileType.ZIP) {
                        throw new ArchiveInspectionException(
                                CannotConfirmReason.NESTED_ARCHIVE_NOT_ALLOWED,
                                "ARCHIVE_NESTING",
                                entryIndex,
                                path.nameDigest(),
                                "Nested ZIP archives are not allowed");
                    }
                    ContentInspection content;
                    try {
                        content = contentInspector.inspect(type, bounded);
                    } catch (ContentInspectionException error) {
                        throw new ArchiveInspectionException(
                                error.reason(),
                                error.failureStage(),
                                entryIndex,
                                path.nameDigest(),
                                "Archive leaf content cannot be confirmed",
                                error);
                    }
                    artifacts.add(new ArchiveEntryArtifact(
                            entryIndex,
                            path.normalizedPath(),
                            entry.getName(),
                            path.leafName(),
                            type,
                            content.sizeBytes(),
                            content.contentDigest()));
                }
                try {
                    zip.closeEntry();
                } catch (ZipException error) {
                    throw zipFailure(error, entryIndex, path.nameDigest());
                }
                long compressed = entry.getCompressedSize();
                enforceRatio(bounded.entryExpanded(), compressed, entryIndex, path.nameDigest());
                totalCompressed = safeAdd(totalCompressed, Math.max(compressed, 0),
                        entryIndex, path.nameDigest(), "ARCHIVE_COMPRESSED_SIZE");
            }
            drainRaw(raw, deadline, entryIndex);
        } catch (ArchiveSafetyIOException error) {
            throw error.failure;
        } catch (ZipException error) {
            throw zipFailure(error, entryIndex, null);
        } catch (IOException error) {
            throw new ArchiveInspectionException(
                    CannotConfirmReason.CORRUPTED,
                    "ARCHIVE_STREAM",
                    entryIndex == 0 ? null : entryIndex,
                    null,
                    "Archive stream is damaged",
                    error);
        }
        if (totalExpanded[0] > 0 && totalCompressed == 0) {
            throw limit("ARCHIVE_COMPRESSION_RATIO", null, null,
                    "Archive compression ratio cannot be verified");
        }
        double aggregateRatio = totalCompressed == 0
                ? 0.0d
                : (double) totalExpanded[0] / (double) totalCompressed;
        if (aggregateRatio > limits.maximumCompressionRatio()) {
            throw limit("ARCHIVE_COMPRESSION_RATIO", null, null,
                    "Archive aggregate compression ratio exceeds the operational limit");
        }
        return new ArchiveInspection(raw.count(), raw.digestHex(), List.copyOf(artifacts));
    }

    private void requireZipSignature(PushbackInputStream input) throws ArchiveInspectionException {
        byte[] signature = new byte[4];
        int offset = 0;
        try {
            while (offset < signature.length) {
                int read = input.read(signature, offset, signature.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            if (offset > 0) {
                input.unread(signature, 0, offset);
            }
        } catch (IOException error) {
            throw new ArchiveInspectionException(
                    CannotConfirmReason.CORRUPTED, "ARCHIVE_SIGNATURE", null, null,
                    "Archive signature cannot be read", error);
        }
        boolean local = offset == 4 && signature[0] == 'P' && signature[1] == 'K'
                && signature[2] == 3 && signature[3] == 4;
        boolean empty = offset == 4 && signature[0] == 'P' && signature[1] == 'K'
                && signature[2] == 5 && signature[3] == 6;
        if (!local && !empty) {
            throw new ArchiveInspectionException(
                    CannotConfirmReason.TYPE_MISMATCH, "ARCHIVE_SIGNATURE", null, null,
                    "File bytes do not match ZIP");
        }
    }

    private void validateMethod(ZipEntry entry, int entryIndex, String nameDigest)
            throws ArchiveInspectionException {
        if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
            throw new ArchiveInspectionException(
                    CannotConfirmReason.ARCHIVE_ENTRY_UNSUPPORTED,
                    "ARCHIVE_COMPRESSION_METHOD",
                    entryIndex,
                    nameDigest,
                    "Archive entry compression method is not supported");
        }
    }

    private void enforceRatio(long expanded, long compressed, int entryIndex, String nameDigest)
            throws ArchiveInspectionException {
        if (expanded == 0) {
            return;
        }
        if (compressed <= 0 || (double) expanded / (double) compressed > limits.maximumCompressionRatio()) {
            throw limit("ARCHIVE_COMPRESSION_RATIO", entryIndex, nameDigest,
                    "Archive entry compression ratio exceeds the operational limit");
        }
    }

    private long safeAdd(
            long left,
            long right,
            Integer entryIndex,
            String nameDigest,
            String stage
    ) throws ArchiveInspectionException {
        if (right < 0 || left > Long.MAX_VALUE - right) {
            throw limit(stage, entryIndex, nameDigest, "Archive size accounting overflowed");
        }
        return left + right;
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) != -1) {
            // bounded stream performs all accounting
        }
    }

    private static void drainRaw(CountingDigestInputStream input, ArchiveDeadline deadline, int entryIndex)
            throws IOException, ArchiveInspectionException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) != -1) {
            deadline.check(entryIndex == 0 ? null : entryIndex, null);
        }
    }

    private static ArchiveInspectionException zipFailure(
            ZipException error,
            Integer entryIndex,
            String nameDigest
    ) {
        String message = error.getMessage();
        CannotConfirmReason reason = message != null
                && message.toLowerCase(Locale.ROOT).contains("encrypt")
                ? CannotConfirmReason.ENCRYPTED
                : CannotConfirmReason.CORRUPTED;
        String stage = reason == CannotConfirmReason.ENCRYPTED
                ? "ARCHIVE_ENCRYPTION"
                : "ARCHIVE_CRC_OR_STRUCTURE";
        return new ArchiveInspectionException(
                reason, stage, entryIndex, nameDigest, "Archive entry cannot be read safely", error);
    }

    private static ArchiveInspectionException limit(
            String stage,
            Integer entryIndex,
            String nameDigest,
            String message
    ) {
        return new ArchiveInspectionException(
                CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                stage,
                entryIndex,
                nameDigest,
                message);
    }

    private static final class BoundedEntryInputStream extends InputStream {
        private final InputStream delegate;
        private final ArchiveSafetyLimits limits;
        private final ArchiveDeadline deadline;
        private final long[] totalExpanded;
        private final int entryIndex;
        private final String nameDigest;
        private long entryExpanded;

        private BoundedEntryInputStream(
                InputStream delegate,
                ArchiveSafetyLimits limits,
                ArchiveDeadline deadline,
                long[] totalExpanded,
                int entryIndex,
                String nameDigest
        ) {
            this.delegate = delegate;
            this.limits = limits;
            this.deadline = deadline;
            this.totalExpanded = totalExpanded;
            this.entryIndex = entryIndex;
            this.nameDigest = nameDigest;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                deadline.check(entryIndex, nameDigest);
            } catch (ArchiveInspectionException error) {
                throw new ArchiveSafetyIOException(error);
            }
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                entryExpanded += read;
                if (entryExpanded > limits.maximumEntryUncompressedBytes()) {
                    throw new ArchiveSafetyIOException(limit(
                            "ARCHIVE_ENTRY_SIZE", entryIndex, nameDigest,
                            "Archive entry expands beyond the operational limit"));
                }
                if (totalExpanded[0] > Long.MAX_VALUE - read) {
                    throw new ArchiveSafetyIOException(limit(
                            "ARCHIVE_TOTAL_SIZE", entryIndex, nameDigest,
                            "Archive total size accounting overflowed"));
                }
                totalExpanded[0] += read;
                if (totalExpanded[0] > limits.maximumTotalUncompressedBytes()) {
                    throw new ArchiveSafetyIOException(limit(
                            "ARCHIVE_TOTAL_SIZE", entryIndex, nameDigest,
                            "Archive expands beyond the total operational limit"));
                }
            }
            return read;
        }

        long entryExpanded() {
            return entryExpanded;
        }
    }

    private static final class CountingDigestInputStream extends InputStream {
        private final InputStream delegate;
        private final MessageDigest digest;
        private long count;
        private boolean finalized;

        private CountingDigestInputStream(InputStream delegate) {
            this.delegate = delegate;
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
            }
            return read;
        }

        long count() {
            return count;
        }

        String digestHex() {
            if (finalized) {
                throw new IllegalStateException("Archive digest was already finalized");
            }
            finalized = true;
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static final class ArchiveSafetyIOException extends IOException {
        private final ArchiveInspectionException failure;

        private ArchiveSafetyIOException(ArchiveInspectionException failure) {
            super(failure.getMessage(), failure);
            this.failure = failure;
        }
    }

}
