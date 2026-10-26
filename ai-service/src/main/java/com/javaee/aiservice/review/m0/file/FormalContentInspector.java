package com.javaee.aiservice.review.m0.file;

import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FormalFileType;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public final class FormalContentInspector {
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final byte[] OLE_MAGIC = new byte[]{
            (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
            (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
    };

    private final ArchiveSafetyLimits embeddedArchiveLimits;
    private final LongSupplier nanoClock;

    public FormalContentInspector(ArchiveSafetyLimits embeddedArchiveLimits) {
        this(embeddedArchiveLimits, System::nanoTime);
    }

    public FormalContentInspector(ArchiveSafetyLimits embeddedArchiveLimits, LongSupplier nanoClock) {
        this.embeddedArchiveLimits = java.util.Objects.requireNonNull(embeddedArchiveLimits);
        this.nanoClock = java.util.Objects.requireNonNull(nanoClock);
    }

    public ContentInspection inspect(FormalFileType type, InputStream input)
            throws IOException, ContentInspectionException {
        if (type == FormalFileType.ZIP) {
            throw new IllegalArgumentException("ZIP containers are inspected by ZipArchiveInspector");
        }
        CountingDigestInputStream counted = new CountingDigestInputStream(input);
        PushbackInputStream pushed = new PushbackInputStream(counted, 8);
        byte[] prefix = readPrefix(pushed, 8);
        pushed.unread(prefix);

        if (type == FormalFileType.DOCX || type == FormalFileType.XLSX) {
            validateOoxml(type, pushed, prefix);
        } else {
            validateFlat(type, pushed);
        }
        return new ContentInspection(counted.count(), counted.digestHex());
    }

    private void validateFlat(FormalFileType type, InputStream input)
            throws IOException, ContentInspectionException {
        byte[] prefix = new byte[8];
        int prefixLength = 0;
        byte[] tail = new byte[16];
        int tailLength = 0;
        StringBuilder tokenWindow = new StringBuilder();
        boolean encryptedPdf = false;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (prefixLength < prefix.length) {
                int copy = Math.min(read, prefix.length - prefixLength);
                System.arraycopy(buffer, 0, prefix, prefixLength, copy);
                prefixLength += copy;
            }
            for (int index = 0; index < read; index++) {
                if (tailLength < tail.length) {
                    tail[tailLength++] = buffer[index];
                } else {
                    System.arraycopy(tail, 1, tail, 0, tail.length - 1);
                    tail[tail.length - 1] = buffer[index];
                }
            }
            if (type == FormalFileType.PDF && !encryptedPdf) {
                tokenWindow.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
                encryptedPdf = tokenWindow.indexOf("/Encrypt") >= 0;
                if (tokenWindow.length() > 32) {
                    tokenWindow.delete(0, tokenWindow.length() - 32);
                }
            }
        }

        switch (type) {
            case PDF -> {
                requirePrefix(prefix, prefixLength, PDF_MAGIC, type);
                if (encryptedPdf) {
                    throw failure(CannotConfirmReason.ENCRYPTED, "PDF_ENCRYPTION", "PDF is encrypted");
                }
                String tailText = new String(tail, 0, tailLength, StandardCharsets.ISO_8859_1);
                if (!tailText.contains("%%EOF")) {
                    throw failure(CannotConfirmReason.CORRUPTED, "PDF_TRAILER", "PDF trailer is missing");
                }
            }
            case JPG -> {
                if (prefixLength < 3 || (prefix[0] & 0xff) != 0xff || (prefix[1] & 0xff) != 0xd8
                        || (prefix[2] & 0xff) != 0xff) {
                    throw typeMismatch(type);
                }
                if (tailLength < 2 || (tail[tailLength - 2] & 0xff) != 0xff
                        || (tail[tailLength - 1] & 0xff) != 0xd9) {
                    throw failure(CannotConfirmReason.CORRUPTED, "JPG_TRAILER", "JPG end marker is missing");
                }
            }
            case PNG -> {
                requirePrefix(prefix, prefixLength, PNG_MAGIC, type);
                if (!containsIend(tail, tailLength)) {
                    throw failure(CannotConfirmReason.CORRUPTED, "PNG_TRAILER", "PNG IEND marker is missing");
                }
            }
            default -> throw new IllegalArgumentException("Unexpected flat formal file type: " + type);
        }
    }

    private void validateOoxml(FormalFileType type, InputStream input, byte[] prefix)
            throws IOException, ContentInspectionException {
        if (startsWith(prefix, OLE_MAGIC)) {
            throw failure(CannotConfirmReason.ENCRYPTED, "OOXML_ENCRYPTION", "Office document is encrypted");
        }
        if (!isZipSignature(prefix)) {
            throw typeMismatch(type);
        }

        long startedAt = nanoClock.getAsLong();
        int entryCount = 0;
        long totalExpanded = 0;
        long totalCompressed = 0;
        boolean contentTypes = false;
        boolean mainPart = false;
        ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkEmbeddedDeadline(startedAt);
                entryCount++;
                if (entryCount > embeddedArchiveLimits.maximumEntries()) {
                    throw failure(CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                            "OOXML_ENTRY_COUNT", "Office package has too many entries");
                }
                String name = entry.getName();
                validateEmbeddedPath(name);
                if (name.getBytes(StandardCharsets.UTF_8).length
                        > embeddedArchiveLimits.maximumNameUtf8Bytes()) {
                    throw failure(CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                            "OOXML_ENTRY_NAME", "Office package entry name is too long");
                }
                if (!entry.isDirectory()) {
                    long expanded = drainEmbeddedEntry(zip, startedAt);
                    totalExpanded = safeAdd(totalExpanded, expanded, "OOXML_TOTAL_SIZE");
                    if (totalExpanded > embeddedArchiveLimits.maximumTotalUncompressedBytes()) {
                        throw failure(CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                                "OOXML_TOTAL_SIZE", "Office package expands beyond the operational limit");
                    }
                    long compressed = entry.getCompressedSize();
                    enforceRatio(expanded, compressed, "OOXML_COMPRESSION_RATIO");
                    totalCompressed = safeAdd(totalCompressed, Math.max(compressed, 0), "OOXML_COMPRESSED_SIZE");
                    String normalized = name.replace('\\', '/');
                    contentTypes |= normalized.equals("[Content_Types].xml");
                    mainPart |= type == FormalFileType.DOCX && normalized.equals("word/document.xml")
                            || type == FormalFileType.XLSX && normalized.equals("xl/workbook.xml");
                }
                zip.closeEntry();
            }
        } catch (ZipException error) {
            if (isEncryptedZipError(error)) {
                throw failure(CannotConfirmReason.ENCRYPTED, "OOXML_ENCRYPTION",
                        "Office package contains an encrypted entry", error);
            }
            throw failure(CannotConfirmReason.CORRUPTED, "OOXML_ZIP",
                    "Office package is damaged", error);
        }
        while (input.read() != -1) {
            checkEmbeddedDeadline(startedAt);
        }
        if (totalExpanded > 0 && totalCompressed == 0) {
            throw failure(CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                    "OOXML_COMPRESSION_RATIO", "Office package compression ratio cannot be verified");
        }
        if (!contentTypes || !mainPart) {
            throw typeMismatch(type);
        }
    }

    private long drainEmbeddedEntry(InputStream input, long startedAt)
            throws IOException, ContentInspectionException {
        long expanded = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkEmbeddedDeadline(startedAt);
            expanded += read;
            if (expanded > embeddedArchiveLimits.maximumEntryUncompressedBytes()) {
                throw failure(CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                        "OOXML_ENTRY_SIZE", "Office package entry expands beyond the operational limit");
            }
        }
        return expanded;
    }

    private void validateEmbeddedPath(String name) throws ContentInspectionException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.startsWith("\\")
                || name.matches("^[A-Za-z]:.*") || name.indexOf('\\') >= 0) {
            throw failure(CannotConfirmReason.CORRUPTED, "OOXML_ENTRY_PATH",
                    "Office package contains an unsafe entry path");
        }
        String[] segments = name.replaceAll("/$", "").split("/", -1);
        int depth = Math.max(0, segments.length - 1);
        if (depth > embeddedArchiveLimits.maximumDirectoryDepth()) {
            throw failure(CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                    "OOXML_DIRECTORY_DEPTH", "Office package directory depth exceeds the operational limit");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw failure(CannotConfirmReason.CORRUPTED, "OOXML_ENTRY_PATH",
                        "Office package contains an unsafe entry path");
            }
        }
    }

    private void checkEmbeddedDeadline(long startedAt) throws ContentInspectionException {
        long elapsed = nanoClock.getAsLong() - startedAt;
        if (elapsed < 0 || elapsed > embeddedArchiveLimits.maximumProcessingTime().toNanos()) {
            throw failure(CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                    "OOXML_PROCESSING_TIME", "Office package processing exceeded the operational deadline");
        }
    }

    private long safeAdd(long left, long right, String stage) throws ContentInspectionException {
        if (right < 0 || left > Long.MAX_VALUE - right) {
            throw failure(CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                    stage, "Office package size accounting overflowed");
        }
        return left + right;
    }

    private void enforceRatio(long expanded, long compressed, String stage) throws ContentInspectionException {
        if (expanded == 0) {
            return;
        }
        if (compressed <= 0
                || (double) expanded / (double) compressed > embeddedArchiveLimits.maximumCompressionRatio()) {
            throw failure(CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                    stage, "Office package compression ratio exceeds the operational limit");
        }
    }

    private static byte[] readPrefix(InputStream input, int maximum) throws IOException {
        byte[] bytes = new byte[maximum];
        int offset = 0;
        while (offset < maximum) {
            int read = input.read(bytes, offset, maximum - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }
        return offset == bytes.length ? bytes : Arrays.copyOf(bytes, offset);
    }

    private static void requirePrefix(byte[] actual, int actualLength, byte[] expected, FormalFileType type)
            throws ContentInspectionException {
        if (actualLength < expected.length || !startsWith(actual, expected)) {
            throw typeMismatch(type);
        }
    }

    private static boolean containsIend(byte[] tail, int length) {
        for (int index = 0; index <= length - 4; index++) {
            if (tail[index] == 'I' && tail[index + 1] == 'E'
                    && tail[index + 2] == 'N' && tail[index + 3] == 'D') {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] actual, byte[] expected) {
        if (actual.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (actual[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZipSignature(byte[] prefix) {
        return prefix.length >= 4 && prefix[0] == 'P' && prefix[1] == 'K'
                && ((prefix[2] == 3 && prefix[3] == 4) || (prefix[2] == 5 && prefix[3] == 6));
    }

    private static boolean isEncryptedZipError(ZipException error) {
        String message = error.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("encrypt");
    }

    private static ContentInspectionException typeMismatch(FormalFileType type) {
        return failure(CannotConfirmReason.TYPE_MISMATCH, "FILE_SIGNATURE",
                "File bytes do not match " + type.name());
    }

    private static ContentInspectionException failure(
            CannotConfirmReason reason,
            String stage,
            String message
    ) {
        return new ContentInspectionException(reason, stage, message);
    }

    private static ContentInspectionException failure(
            CannotConfirmReason reason,
            String stage,
            String message,
            Throwable cause
    ) {
        return new ContentInspectionException(reason, stage, message, cause);
    }

    private static final class CountingDigestInputStream extends InputStream {
        private final InputStream delegate;
        private final MessageDigest digest;
        private long count;
        private boolean digestRead;

        private CountingDigestInputStream(InputStream delegate) {
            this.delegate = java.util.Objects.requireNonNull(delegate);
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

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        long count() {
            return count;
        }

        String digestHex() {
            if (digestRead) {
                throw new IllegalStateException("Content digest was already finalized");
            }
            digestRead = true;
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
