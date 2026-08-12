package com.javaee.aiservice.review.m0.baseline;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Frozen target names for downstream owners. Every method is intentionally
 * disabled because the current production capability does not exist or the
 * required real dependency was not run. Empty passing assertions are forbidden.
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

    @Disabled("PENDING_IMPLEMENTATION M0-04: add digest-bound streaming lookup for exactly one ZIP leaf")
    @Test
    void zipReaderStreamsOnlyTheRequestedDigestBoundLeaf() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-04: reject traversal, absolute paths, NUL and duplicate normalized/NFC paths")
    @Test
    void zipReaderRejectsUnsafeAndDuplicateNormalizedPaths() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-04: reject nested ZIP, encrypted/corrupt entries and unsupported leaf types")
    @Test
    void zipReaderRejectsNestedEncryptedCorruptAndUnsupportedEntries() {
    }

    @Disabled("PENDING_IMPLEMENTATION M0-04: enforce configurable entry count, item/total expansion and compression-ratio limits")
    @Test
    void zipReaderEnforcesAllExpansionSafetyLimits() {
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
}
