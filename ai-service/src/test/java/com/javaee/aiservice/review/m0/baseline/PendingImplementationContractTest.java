package com.javaee.aiservice.review.m0.baseline;

import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Frozen target names for downstream owners. Targets stay disabled while the
 * capability or required real dependency is absent; delivered targets become
 * positive evidence pointers and must never be replaced by empty assertions.
 */
class PendingImplementationContractTest {

    @Disabled("PENDING_IMPLEMENTATION M0-04: add authenticated per-file owner lookup; bucket/header fallback is insufficient")
    @Test
    void ownedFileLookupRejectsAnonymousAndCrossUserAccess() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-04: one owned snapshot must bind originalFileName, bytes, size, storage version and SHA-256")
    @Test
    void ownedFileSnapshotProvidesConsistentOriginalNameBytesSizeAndSha256() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-04: ordinary file reads must expose a bounded stream instead of byte[] materialization")
    @Test
    void ordinaryFileReadIsStreamingAndBounded() {
    }

    @Test
    void zipReaderStreamsOnlyTheRequestedDigestBoundLeaf() {
        assertThat(zipEntryOpenerSource())
                .contains("new ZipInputStream(", "expectedEntryDigest", "TargetEntryInputStream");
        assertThat(zipAcceptanceTestSource())
                .contains("void singleEntryOpenerReturnsOnlyTargetBytesAndVerifiesDigest()")
                .contains("void singleEntryOpenerRejectsDigestMismatch()")
                .contains("void singleEntryOpenerDoesNotCompleteWhenLaterEntryIsDangerous()");
    }

    @Test
    void zipReaderRejectsUnsafeAndDuplicateNormalizedPaths() {
        assertThat(RepositorySourceEvidence.read(
                "ai-service/src/main/java/com/javaee/aiservice/review/m0/archive/ArchivePath.java"))
                .contains("Normalizer.Form.NFC", "ARCHIVE_ENTRY_PATH", "codePoint == 0", "Character.isISOControl");
        assertThat(zipAcceptanceTestSource())
                .contains("void traversalPathIsRejected()")
                .contains("void absoluteUnixPathIsRejected()")
                .contains("void driveAndUncPathsAreRejected()")
                .contains("void nulAndControlCharactersAreRejected()")
                .contains("void duplicateNfcNormalizedPathsAreRejected()");
    }

    @Test
    void zipReaderRejectsNestedEncryptedCorruptAndUnsupportedEntries() {
        assertThat(RepositorySourceEvidence.read(
                "ai-service/src/main/java/com/javaee/aiservice/review/m0/archive/ZipArchiveInspector.java"))
                .contains("NESTED_ARCHIVE_NOT_ALLOWED")
                .contains("ARCHIVE_ENTRY_UNSUPPORTED")
                .contains("CannotConfirmReason.CORRUPTED")
                .contains("CannotConfirmReason.ENCRYPTED");
        assertThat(zipAcceptanceTestSource())
                .contains("void nestedZipIsRejected()")
                .contains("void encryptedEntryIsRejectedAsEncrypted()")
                .contains("void damagedEntryOrCrcIsRejectedAsCorrupted()")
                .contains("void unsupportedLeafIsRejected()");
    }

    @Test
    void zipReaderEnforcesAllExpansionSafetyLimits() {
        ArchiveSafetyLimits limits = ArchiveSafetyLimits.secureDefaults();
        assertThat(limits.maximumEntries()).isPositive();
        assertThat(limits.maximumEntryUncompressedBytes()).isPositive();
        assertThat(limits.maximumTotalUncompressedBytes())
                .isGreaterThanOrEqualTo(limits.maximumEntryUncompressedBytes());
        assertThat(limits.maximumCompressionRatio()).isGreaterThanOrEqualTo(1.0d);
        assertThat(limits.maximumProcessingTime()).isPositive();
        assertThat(zipAcceptanceTestSource())
                .contains("void entryCountLimitFailsClosed()")
                .contains("void singleEntryExpansionLimitFailsClosed()")
                .contains("void totalExpansionLimitFailsClosed()")
                .contains("void compressionRatioLimitFailsClosed()")
                .contains("void entryNameLengthLimitFailsClosed()")
                .contains("void directoryDepthLimitFailsClosed()")
                .contains("void processingTimeLimitFailsClosed()");
    }

    @Disabled("PENDING_IMPLEMENTATION M0-03 parser adapter: retain reliable PDF page/block locator without inventing public fields")
    @Test
    void pdfExtractionRetainsFrozenSourceLocatorEvidence() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-03 parser adapter: retain DOCX body/table location and source order")
    @Test
    void docxExtractionRetainsOrderAndFrozenSourceLocatorEvidence() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-03 parser adapter: extract XLSX sheet/cellRange using the frozen sourceLocator")
    @Test
    void xlsxExtractionProvidesSheetAndCellRangeEvidence() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-03 parser adapter: expose bounded OCR evidence and separate dependency failure from OCR_INSUFFICIENT")
    @Test
    void imageExtractionHasReliableLocatorAndTypedOcrFailureSemantics() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-03 parser adapter: replace raw UTF-8 fallback with frozen typed failure mapping")
    @Test
    void parserFailuresMapToFrozenCannotConfirmOrSystemFailureSemantics() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-02: Review M0 must use its own isolated versioned JDBC schema mechanism")
    @Test
    void reviewM0SchemaUsesAnIsolatedVersionedJdbcMigration() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-06: Review M0 errors add requestId and safe details while preserving real HTTP statuses")
    @Test
    void reviewM0ApiUsesTheFrozenFourFieldErrorEnvelope() {
    }

    @Disabled("EXTERNAL_VERIFICATION_PENDING M0-02/integration: run startup and migrations against a real MySQL 8 instance")
    @Test
    void realMysql8StartupAndMigrationRemainUnverified() {
    }

    private static String zipEntryOpenerSource() {
        return RepositorySourceEvidence.read(
                "ai-service/src/main/java/com/javaee/aiservice/review/m0/archive/ZipArchiveEntryOpener.java");
    }

    private static String zipAcceptanceTestSource() {
        return RepositorySourceEvidence.read(
                "ai-service/src/test/java/com/javaee/aiservice/review/m0/archive/ZipArchiveInspectorTest.java");
    }
}
