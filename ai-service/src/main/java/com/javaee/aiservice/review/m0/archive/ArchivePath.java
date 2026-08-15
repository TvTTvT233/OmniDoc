package com.javaee.aiservice.review.m0.archive;

import com.javaee.aiservice.review.m0.model.CannotConfirmReason;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

final class ArchivePath {
    private ArchivePath() {
    }

    static ValidatedPath validate(String rawName, boolean directory, int entryIndex, ArchiveSafetyLimits limits)
            throws ArchiveInspectionException {
        String nameDigest = digestName(rawName);
        if (rawName == null || rawName.isEmpty()) {
            throw unsafe(entryIndex, nameDigest, "Archive entry has an empty name");
        }
        if (rawName.getBytes(StandardCharsets.UTF_8).length > limits.maximumNameUtf8Bytes()) {
            throw limit(entryIndex, nameDigest, "Archive entry name exceeds the operational limit");
        }
        if (rawName.startsWith("/") || rawName.startsWith("\\")
                || rawName.matches("^[A-Za-z]:.*") || rawName.indexOf('\\') >= 0) {
            throw unsafe(entryIndex, nameDigest, "Archive entry uses an absolute or platform path");
        }
        for (int offset = 0; offset < rawName.length();) {
            int codePoint = rawName.codePointAt(offset);
            if (codePoint == 0 || Character.isISOControl(codePoint)) {
                throw unsafe(entryIndex, nameDigest, "Archive entry contains a control character");
            }
            offset += Character.charCount(codePoint);
        }

        String withoutTrailingSlash = directory && rawName.endsWith("/")
                ? rawName.substring(0, rawName.length() - 1)
                : rawName;
        if (withoutTrailingSlash.isEmpty()) {
            throw unsafe(entryIndex, nameDigest, "Archive root directory entries are not accepted");
        }
        String[] segments = withoutTrailingSlash.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw unsafe(entryIndex, nameDigest, "Archive entry contains an empty or dot path segment");
            }
        }
        int directoryDepth = directory ? segments.length : segments.length - 1;
        if (directoryDepth > limits.maximumDirectoryDepth()) {
            throw limit(entryIndex, nameDigest, "Archive entry directory depth exceeds the operational limit");
        }
        String normalized = Normalizer.normalize(withoutTrailingSlash, Normalizer.Form.NFC);
        String leafName = segments[segments.length - 1];
        leafName = Normalizer.normalize(leafName, Normalizer.Form.NFC);
        return new ValidatedPath(normalized, leafName, nameDigest);
    }

    static String digestName(String value) {
        try {
            byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static ArchiveInspectionException unsafe(int entryIndex, String nameDigest, String message) {
        return new ArchiveInspectionException(
                CannotConfirmReason.CORRUPTED,
                "ARCHIVE_ENTRY_PATH",
                entryIndex,
                nameDigest,
                message);
    }

    private static ArchiveInspectionException limit(int entryIndex, String nameDigest, String message) {
        return new ArchiveInspectionException(
                CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                "ARCHIVE_SAFETY_LIMIT",
                entryIndex,
                nameDigest,
                message);
    }

    record ValidatedPath(String normalizedPath, String leafName, String nameDigest) {
    }
}
