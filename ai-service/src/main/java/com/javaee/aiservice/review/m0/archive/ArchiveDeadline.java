package com.javaee.aiservice.review.m0.archive;

import com.javaee.aiservice.review.m0.model.CannotConfirmReason;

import java.util.function.LongSupplier;

final class ArchiveDeadline {
    private final long startedAt;
    private final long maximumNanos;
    private final LongSupplier nanoClock;

    ArchiveDeadline(ArchiveSafetyLimits limits, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.startedAt = nanoClock.getAsLong();
        this.maximumNanos = limits.maximumProcessingTime().toNanos();
    }

    void check(Integer entryIndex, String entryNameDigest) throws ArchiveInspectionException {
        long elapsed = nanoClock.getAsLong() - startedAt;
        if (elapsed < 0 || elapsed > maximumNanos) {
            throw new ArchiveInspectionException(
                    CannotConfirmReason.ARCHIVE_SAFETY_LIMIT_EXCEEDED,
                    "ARCHIVE_PROCESSING_TIME",
                    entryIndex,
                    entryNameDigest,
                    "Archive processing exceeded the operational deadline");
        }
    }
}
