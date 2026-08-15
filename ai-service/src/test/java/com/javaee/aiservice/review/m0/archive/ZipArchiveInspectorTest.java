package com.javaee.aiservice.review.m0.archive;

import com.javaee.aiservice.review.m0.file.FormalContentInspector;
import com.javaee.aiservice.review.m0.file.SyntheticFileFixtures;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.javaee.aiservice.review.m0.file.SyntheticFileFixtures.entry;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipArchiveInspectorTest {
    private final ArchiveSafetyLimits defaults = ArchiveSafetyLimits.secureDefaults();
    private final FormalContentInspector contentInspector = new FormalContentInspector(defaults);
    private final ZipArchiveInspector inspector = new ZipArchiveInspector(defaults, contentInspector);

    @Test
    void fiveFormalLeafTypesProduceIndependentStableMaterialIdentities() throws Exception {
        byte[] pdf = SyntheticFileFixtures.pdf("zip-pdf");
        byte[] docx = SyntheticFileFixtures.docx("zip-docx");
        byte[] xlsx = SyntheticFileFixtures.xlsx("zip-xlsx");
        byte[] jpg = SyntheticFileFixtures.jpg((byte) 7);
        byte[] png = SyntheticFileFixtures.png((byte) 8);
        byte[] archive = SyntheticFileFixtures.zip(List.of(
                SyntheticFileFixtures.directory("folder"),
                entry("folder/form.pdf", pdf),
                entry("letter.docx", docx),
                entry("table.xlsx", xlsx),
                entry("photo.jpg", jpg),
                entry("scan.png", png)));

        ArchiveInspection first = inspector.inspect(new ByteArrayInputStream(archive));
        ArchiveInspection second = inspector.inspect(new ByteArrayInputStream(archive));

        assertEquals(5, first.entries().size());
        assertEquals(List.of(
                        FormalFileType.PDF, FormalFileType.DOCX, FormalFileType.XLSX,
                        FormalFileType.JPG, FormalFileType.PNG),
                first.entries().stream().map(ArchiveEntryArtifact::formalFileType).toList());
        assertEquals(first, second);
        assertEquals(SyntheticFileFixtures.sha256(archive), first.archiveContentDigest());
        assertEquals(SyntheticFileFixtures.sha256(pdf), first.entries().get(0).contentDigest());
        assertEquals("folder/form.pdf", first.entries().get(0).normalizedEntryPath());
        assertEquals(2, first.entries().get(0).entryIndex());
    }

    @Test
    void nestedZipIsRejected() {
        assertReason(CannotConfirmReason.NESTED_ARCHIVE_NOT_ALLOWED,
                SyntheticFileFixtures.zip(List.of(entry("nested.zip", new byte[]{'P', 'K', 5, 6}))));
    }

    @Test
    void unsupportedLeafIsRejected() {
        assertReason(CannotConfirmReason.ARCHIVE_ENTRY_UNSUPPORTED,
                SyntheticFileFixtures.zip(List.of(entry("legacy.doc", new byte[]{1, 2, 3}))));
    }

    @Test
    void traversalPathIsRejected() {
        assertReason(CannotConfirmReason.CORRUPTED,
                SyntheticFileFixtures.zip(List.of(entry("../escape.pdf", SyntheticFileFixtures.pdf("x")))));
    }

    @Test
    void absoluteUnixPathIsRejected() {
        assertReason(CannotConfirmReason.CORRUPTED,
                SyntheticFileFixtures.zip(List.of(entry("/absolute.pdf", SyntheticFileFixtures.pdf("x")))));
    }

    @Test
    void driveAndUncPathsAreRejected() {
        assertReason(CannotConfirmReason.CORRUPTED,
                SyntheticFileFixtures.zip(List.of(entry("C:/absolute.pdf", SyntheticFileFixtures.pdf("x")))));
        assertReason(CannotConfirmReason.CORRUPTED,
                SyntheticFileFixtures.zip(List.of(entry("\\\\server\\share.pdf", SyntheticFileFixtures.pdf("x")))));
    }

    @Test
    void nulAndControlCharactersAreRejected() {
        assertReason(CannotConfirmReason.CORRUPTED,
                SyntheticFileFixtures.zip(List.of(entry("bad\u0000.pdf", SyntheticFileFixtures.pdf("x")))));
        assertReason(CannotConfirmReason.CORRUPTED,
                SyntheticFileFixtures.zip(List.of(entry("bad\u0001.pdf", SyntheticFileFixtures.pdf("x")))));
    }

    @Test
    void dotDirectoryIsRejected() {
        assertReason(CannotConfirmReason.CORRUPTED,
                SyntheticFileFixtures.zip(List.of(entry("./form.pdf", SyntheticFileFixtures.pdf("x")))));
    }

    @Test
    void duplicateNfcNormalizedPathsAreRejected() {
        assertReason(CannotConfirmReason.CORRUPTED,
                SyntheticFileFixtures.zip(List.of(
                        entry("e\u0301.pdf", SyntheticFileFixtures.pdf("one")),
                        entry("é.pdf", SyntheticFileFixtures.pdf("two")))));
    }

    @Test
    void encryptedEntryIsRejectedAsEncrypted() {
        byte[] ordinary = SyntheticFileFixtures.zip(List.of(
                entry("form.pdf", SyntheticFileFixtures.pdf("encrypted"))));
        assertReason(CannotConfirmReason.ENCRYPTED,
                SyntheticFileFixtures.encryptedFirstEntry(ordinary));
    }

    @Test
    void damagedEntryOrCrcIsRejectedAsCorrupted() {
        byte[] ordinary = SyntheticFileFixtures.zip(List.of(
                entry("form.pdf", SyntheticFileFixtures.pdf("crc"))));
        assertReason(CannotConfirmReason.CORRUPTED,
                SyntheticFileFixtures.corruptFirstEntryData(ordinary));
    }

    @Test
    void invalidZipSignatureIsTypeMismatch() {
        assertReason(CannotConfirmReason.TYPE_MISMATCH, new byte[]{1, 2, 3, 4});
    }

    @Test
    void entryCountLimitFailsClosed() {
        ArchiveSafetyLimits limits = limits(1, 1024, 2048, 100, 100, 4, Duration.ofSeconds(1));
        assertReason(new ZipArchiveInspector(limits, new FormalContentInspector(limits)),
                CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                SyntheticFileFixtures.zip(List.of(
                        entry("one.pdf", SyntheticFileFixtures.pdf("1")),
                        entry("two.pdf", SyntheticFileFixtures.pdf("2")))));
    }

    @Test
    void singleEntryExpansionLimitFailsClosed() {
        ArchiveSafetyLimits limits = limits(2, 5, 10, 100, 100, 4, Duration.ofSeconds(1));
        assertReason(new ZipArchiveInspector(limits, new FormalContentInspector(limits)),
                CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                SyntheticFileFixtures.zip(List.of(entry("one.pdf", SyntheticFileFixtures.pdf("large")))));
    }

    @Test
    void totalExpansionLimitFailsClosed() {
        ArchiveSafetyLimits limits = limits(3, 64, 70, 100, 100, 4, Duration.ofSeconds(1));
        assertReason(new ZipArchiveInspector(limits, new FormalContentInspector(limits)),
                CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                SyntheticFileFixtures.zip(List.of(
                        entry("one.pdf", SyntheticFileFixtures.pdf("first-total")),
                        entry("two.pdf", SyntheticFileFixtures.pdf("second-total")))));
    }

    @Test
    void compressionRatioLimitFailsClosed() {
        ArchiveSafetyLimits limits = limits(2, 8192, 8192, 2, 100, 4, Duration.ofSeconds(1));
        byte[] compressed = SyntheticFileFixtures.pdf("0".repeat(4096));
        assertReason(new ZipArchiveInspector(limits, new FormalContentInspector(limits)),
                CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                SyntheticFileFixtures.zip(List.of(entry("bomb.pdf", compressed))));
    }

    @Test
    void entryNameLengthLimitFailsClosed() {
        ArchiveSafetyLimits limits = limits(2, 1024, 2048, 100, 8, 4, Duration.ofSeconds(1));
        assertReason(new ZipArchiveInspector(limits, new FormalContentInspector(limits)),
                CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                SyntheticFileFixtures.zip(List.of(entry("123456789.pdf", SyntheticFileFixtures.pdf("x")))));
    }

    @Test
    void directoryDepthLimitFailsClosed() {
        ArchiveSafetyLimits limits = limits(2, 1024, 2048, 100, 100, 1, Duration.ofSeconds(1));
        assertReason(new ZipArchiveInspector(limits, new FormalContentInspector(limits)),
                CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                SyntheticFileFixtures.zip(List.of(entry("a/b/c.pdf", SyntheticFileFixtures.pdf("x")))));
    }

    @Test
    void processingTimeLimitFailsClosed() {
        ArchiveSafetyLimits limits = limits(2, 1024, 2048, 100, 100, 4, Duration.ofNanos(1));
        AtomicLong clock = new AtomicLong();
        ZipArchiveInspector timed = new ZipArchiveInspector(
                limits, new FormalContentInspector(limits), clock::getAndIncrement);
        assertReason(timed, CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                SyntheticFileFixtures.zip(List.of(entry("one.pdf", SyntheticFileFixtures.pdf("time")))));
    }

    @Test
    void singleEntryOpenerReturnsOnlyTargetBytesAndVerifiesDigest() throws Exception {
        byte[] one = SyntheticFileFixtures.pdf("one");
        byte[] two = SyntheticFileFixtures.png((byte) 2);
        byte[] archive = SyntheticFileFixtures.zip(List.of(entry("one.pdf", one), entry("two.png", two)));
        ZipArchiveEntryOpener opener = new ZipArchiveEntryOpener(defaults);

        try (InputStream input = opener.open(
                new ByteArrayInputStream(archive), "two.png", SyntheticFileFixtures.sha256(two))) {
            assertArrayEquals(two, input.readAllBytes());
        }
    }

    @Test
    void singleEntryOpenerRejectsDigestMismatch() throws Exception {
        byte[] one = SyntheticFileFixtures.pdf("one");
        byte[] archive = SyntheticFileFixtures.zip(List.of(entry("one.pdf", one)));
        ZipArchiveEntryOpener opener = new ZipArchiveEntryOpener(defaults);

        try (InputStream input = opener.open(new ByteArrayInputStream(archive), "one.pdf", "0".repeat(64))) {
            ArchiveEntryReadException error = assertThrows(ArchiveEntryReadException.class, input::readAllBytes);
            assertEquals(CannotConfirmReason.CORRUPTED, error.reason());
        }
    }

    @Test
    void singleEntryOpenerDoesNotCompleteWhenLaterEntryIsDangerous() throws Exception {
        byte[] target = SyntheticFileFixtures.pdf("target");
        byte[] archive = SyntheticFileFixtures.zip(List.of(
                entry("target.pdf", target),
                entry("later.zip", new byte[]{'P', 'K', 5, 6})));
        ZipArchiveEntryOpener opener = new ZipArchiveEntryOpener(defaults);

        try (InputStream input = opener.open(
                new ByteArrayInputStream(archive), "target.pdf", SyntheticFileFixtures.sha256(target))) {
            ArchiveEntryReadException error = assertThrows(ArchiveEntryReadException.class, input::readAllBytes);
            assertEquals(CannotConfirmReason.NESTED_ARCHIVE_NOT_ALLOWED, error.reason());
        }
    }

    private void assertReason(CannotConfirmReason reason, byte[] archive) {
        assertReason(inspector, reason, archive);
    }

    private static void assertReason(
            ZipArchiveInspector target,
            CannotConfirmReason reason,
            byte[] archive
    ) {
        ArchiveInspectionException error = assertThrows(
                ArchiveInspectionException.class,
                () -> target.inspect(new ByteArrayInputStream(archive)));
        assertEquals(reason, error.reason());
        assertTrue(error.failureStage() != null && !error.failureStage().isBlank());
    }

    private static ArchiveSafetyLimits limits(
            int entries,
            long entryBytes,
            long totalBytes,
            double ratio,
            int nameBytes,
            int depth,
            Duration time
    ) {
        return new ArchiveSafetyLimits(entries, entryBytes, totalBytes, ratio, nameBytes, depth, time);
    }
}
