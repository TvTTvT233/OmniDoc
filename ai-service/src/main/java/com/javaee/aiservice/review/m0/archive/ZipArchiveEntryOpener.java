package com.javaee.aiservice.review.m0.archive;

import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FormalFileType;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public final class ZipArchiveEntryOpener {
    private final ArchiveSafetyLimits limits;
    private final LongSupplier nanoClock;

    public ZipArchiveEntryOpener(ArchiveSafetyLimits limits) {
        this(limits, System::nanoTime);
    }

    public ZipArchiveEntryOpener(ArchiveSafetyLimits limits, LongSupplier nanoClock) {
        this.limits = java.util.Objects.requireNonNull(limits);
        this.nanoClock = java.util.Objects.requireNonNull(nanoClock);
    }

    public InputStream open(
            InputStream archiveInput,
            String normalizedEntryPath,
            String expectedEntryDigest
    ) throws ArchiveInspectionException {
        if (normalizedEntryPath == null || normalizedEntryPath.isBlank()
                || expectedEntryDigest == null || !expectedEntryDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Stable ZIP entry identity is required");
        }
        ArchiveDeadline deadline = new ArchiveDeadline(limits, nanoClock);
        PushbackInputStream pushed = new PushbackInputStream(archiveInput, 4);
        requireZipSignature(pushed);
        Context context = new Context(
                new ZipInputStream(pushed, StandardCharsets.UTF_8),
                archiveInput,
                deadline,
                new HashSet<>(),
                new long[]{0});
        try {
            while (true) {
                EntryContext entry = nextEntry(context);
                if (entry == null) {
                    throw new ArchiveInspectionException(
                            CannotConfirmReason.SOURCE_FILE_UNAVAILABLE,
                            "ARCHIVE_ENTRY_IDENTITY",
                            null,
                            null,
                            "Expected ZIP entry no longer exists");
                }
                if (entry.directory) {
                    drain(entry.bounded);
                    finishEntry(context, entry);
                    continue;
                }
                FormalFileType type = FormalFileType.fromFileName(entry.path.leafName()).orElse(null);
                if (type == null) {
                    throw new ArchiveInspectionException(
                            CannotConfirmReason.ARCHIVE_ENTRY_UNSUPPORTED,
                            "ARCHIVE_ENTRY_TYPE",
                            entry.index,
                            entry.path.nameDigest(),
                            "Archive leaf type is not supported");
                }
                if (type == FormalFileType.ZIP) {
                    throw new ArchiveInspectionException(
                            CannotConfirmReason.NESTED_ARCHIVE_NOT_ALLOWED,
                            "ARCHIVE_NESTING",
                            entry.index,
                            entry.path.nameDigest(),
                            "Nested ZIP archives are not allowed");
                }
                if (entry.path.normalizedPath().equals(normalizedEntryPath)) {
                    return new TargetEntryInputStream(context, entry, expectedEntryDigest);
                }
                drain(entry.bounded);
                finishEntry(context, entry);
            }
        } catch (ArchiveSafetyIOException error) {
            closeQuietly(context.zip);
            throw error.failure;
        } catch (ZipException error) {
            closeQuietly(context.zip);
            throw zipFailure(error, context.entryIndex, null);
        } catch (IOException error) {
            closeQuietly(context.zip);
            throw new ArchiveInspectionException(
                    CannotConfirmReason.CORRUPTED,
                    "ARCHIVE_STREAM",
                    context.entryIndex == 0 ? null : context.entryIndex,
                    null,
                    "Archive stream is damaged",
                    error);
        } catch (ArchiveInspectionException error) {
            closeQuietly(context.zip);
            throw error;
        }
    }

    private EntryContext nextEntry(Context context) throws IOException, ArchiveInspectionException {
        context.deadline.check(context.entryIndex == 0 ? null : context.entryIndex, null);
        ZipEntry entry;
        try {
            entry = context.zip.getNextEntry();
        } catch (ZipException error) {
            throw zipFailure(error, context.entryIndex + 1, null);
        }
        if (entry == null) {
            return null;
        }
        context.entryIndex++;
        if (context.entryIndex > limits.maximumEntries()) {
            throw limit("ARCHIVE_ENTRY_COUNT", context.entryIndex, ArchivePath.digestName(entry.getName()),
                    "Archive contains too many entries");
        }
        ArchivePath.ValidatedPath path = ArchivePath.validate(
                entry.getName(), entry.isDirectory(), context.entryIndex, limits);
        if (!context.normalizedPaths.add(path.normalizedPath())) {
            throw new ArchiveInspectionException(
                    CannotConfirmReason.CORRUPTED,
                    "ARCHIVE_DUPLICATE_NFC_PATH",
                    context.entryIndex,
                    path.nameDigest(),
                    "Archive contains duplicate Unicode-normalized paths");
        }
        if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
            throw new ArchiveInspectionException(
                    CannotConfirmReason.ARCHIVE_ENTRY_UNSUPPORTED,
                    "ARCHIVE_COMPRESSION_METHOD",
                    context.entryIndex,
                    path.nameDigest(),
                    "Archive entry compression method is not supported");
        }
        if (entry.getSize() > limits.maximumEntryUncompressedBytes()) {
            throw limit("ARCHIVE_ENTRY_SIZE", context.entryIndex, path.nameDigest(),
                    "Archive entry expands beyond the operational limit");
        }
        BoundedEntryInputStream bounded = new BoundedEntryInputStream(
                context.zip, context.deadline, context.totalExpanded,
                context.entryIndex, path.nameDigest());
        return new EntryContext(context.entryIndex, entry.isDirectory(), entry, path, bounded);
    }

    private void finishEntry(Context context, EntryContext entry) throws IOException, ArchiveInspectionException {
        try {
            context.zip.closeEntry();
        } catch (ZipException error) {
            throw zipFailure(error, entry.index, entry.path.nameDigest());
        }
        long compressed = entry.entry.getCompressedSize();
        if (entry.bounded.entryExpanded > 0
                && (compressed <= 0
                || (double) entry.bounded.entryExpanded / (double) compressed > limits.maximumCompressionRatio())) {
            throw limit("ARCHIVE_COMPRESSION_RATIO", entry.index, entry.path.nameDigest(),
                    "Archive entry compression ratio exceeds the operational limit");
        }
        if (compressed > 0) {
            if (context.totalCompressed > Long.MAX_VALUE - compressed) {
                throw limit("ARCHIVE_COMPRESSED_SIZE", entry.index, entry.path.nameDigest(),
                        "Archive compressed size accounting overflowed");
            }
            context.totalCompressed += compressed;
        }
    }

    private void finishArchive(Context context) throws IOException, ArchiveInspectionException {
        while (true) {
            EntryContext entry = nextEntry(context);
            if (entry == null) {
                break;
            }
            if (!entry.directory) {
                FormalFileType type = FormalFileType.fromFileName(entry.path.leafName()).orElse(null);
                if (type == null) {
                    throw new ArchiveInspectionException(
                            CannotConfirmReason.ARCHIVE_ENTRY_UNSUPPORTED,
                            "ARCHIVE_ENTRY_TYPE",
                            entry.index,
                            entry.path.nameDigest(),
                            "Archive leaf type is not supported");
                }
                if (type == FormalFileType.ZIP) {
                    throw new ArchiveInspectionException(
                            CannotConfirmReason.NESTED_ARCHIVE_NOT_ALLOWED,
                            "ARCHIVE_NESTING",
                            entry.index,
                            entry.path.nameDigest(),
                            "Nested ZIP archives are not allowed");
                }
            }
            drain(entry.bounded);
            finishEntry(context, entry);
        }
        byte[] buffer = new byte[8192];
        while (context.rawInput.read(buffer) != -1) {
            context.deadline.check(context.entryIndex == 0 ? null : context.entryIndex, null);
        }
        if (context.totalExpanded[0] > 0 && context.totalCompressed == 0) {
            throw limit("ARCHIVE_COMPRESSION_RATIO", null, null,
                    "Archive compression ratio cannot be verified");
        }
        if (context.totalCompressed > 0
                && (double) context.totalExpanded[0] / (double) context.totalCompressed
                > limits.maximumCompressionRatio()) {
            throw limit("ARCHIVE_COMPRESSION_RATIO", null, null,
                    "Archive aggregate compression ratio exceeds the operational limit");
        }
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

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) != -1) {
            // bounded stream performs all accounting
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
        return new ArchiveInspectionException(
                reason,
                reason == CannotConfirmReason.ENCRYPTED ? "ARCHIVE_ENCRYPTION" : "ARCHIVE_CRC_OR_STRUCTURE",
                entryIndex,
                nameDigest,
                "Archive entry cannot be read safely",
                error);
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

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // preserve the primary failure
        }
    }

    private final class BoundedEntryInputStream extends InputStream {
        private final InputStream delegate;
        private final ArchiveDeadline deadline;
        private final long[] totalExpanded;
        private final int entryIndex;
        private final String nameDigest;
        private long entryExpanded;

        private BoundedEntryInputStream(
                InputStream delegate,
                ArchiveDeadline deadline,
                long[] totalExpanded,
                int entryIndex,
                String nameDigest
        ) {
            this.delegate = delegate;
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
    }

    private final class TargetEntryInputStream extends InputStream {
        private final Context context;
        private final EntryContext target;
        private final String expectedDigest;
        private final MessageDigest digest;
        private boolean finished;
        private boolean closed;

        private TargetEntryInputStream(Context context, EntryContext target, String expectedDigest) {
            this.context = context;
            this.target = target;
            this.expectedDigest = expectedDigest;
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
            if (closed) {
                throw new IOException("ZIP entry stream is closed");
            }
            if (finished) {
                return -1;
            }
            try {
                int read = target.bounded.read(bytes, offset, length);
                if (read > 0) {
                    digest.update(bytes, offset, read);
                    return read;
                }
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equals(expectedDigest)) {
                    throw new ArchiveEntryReadException(
                            CannotConfirmReason.CORRUPTED,
                            "ARCHIVE_ENTRY_DIGEST",
                            "ZIP entry digest no longer matches the material snapshot");
                }
                finishEntry(context, target);
                finishArchive(context);
                finished = true;
                context.zip.close();
                closed = true;
                return -1;
            } catch (ArchiveEntryReadException error) {
                closeQuietly(context.zip);
                closed = true;
                throw error;
            } catch (ArchiveSafetyIOException error) {
                closeQuietly(context.zip);
                closed = true;
                throw new ArchiveEntryReadException(
                        error.failure.reason(), error.failure.failureStage(), error.failure.getMessage(), error.failure);
            } catch (ArchiveInspectionException error) {
                closeQuietly(context.zip);
                closed = true;
                throw new ArchiveEntryReadException(
                        error.reason(), error.failureStage(), error.getMessage(), error);
            } catch (ZipException error) {
                closeQuietly(context.zip);
                closed = true;
                ArchiveInspectionException failure = zipFailure(error, target.index, target.path.nameDigest());
                throw new ArchiveEntryReadException(
                        failure.reason(), failure.failureStage(), failure.getMessage(), failure);
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                context.zip.close();
            }
        }
    }

    private static final class Context {
        private final ZipInputStream zip;
        private final InputStream rawInput;
        private final ArchiveDeadline deadline;
        private final Set<String> normalizedPaths;
        private final long[] totalExpanded;
        private int entryIndex;
        private long totalCompressed;

        private Context(
                ZipInputStream zip,
                InputStream rawInput,
                ArchiveDeadline deadline,
                Set<String> normalizedPaths,
                long[] totalExpanded
        ) {
            this.zip = zip;
            this.rawInput = rawInput;
            this.deadline = deadline;
            this.normalizedPaths = normalizedPaths;
            this.totalExpanded = totalExpanded;
        }
    }

    private record EntryContext(
            int index,
            boolean directory,
            ZipEntry entry,
            ArchivePath.ValidatedPath path,
            BoundedEntryInputStream bounded
    ) {
    }

    private static final class ArchiveSafetyIOException extends IOException {
        private final ArchiveInspectionException failure;

        private ArchiveSafetyIOException(ArchiveInspectionException failure) {
            super(failure.getMessage(), failure);
            this.failure = failure;
        }
    }
}
