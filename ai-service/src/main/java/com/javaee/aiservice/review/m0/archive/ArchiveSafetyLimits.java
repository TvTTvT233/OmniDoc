package com.javaee.aiservice.review.m0.archive;

import java.time.Duration;

/**
 * Operational fail-closed limits for archive processing. These defaults are security
 * guardrails, not product capacity commitments.
 */
public record ArchiveSafetyLimits(
        int maximumEntries,
        long maximumEntryUncompressedBytes,
        long maximumTotalUncompressedBytes,
        double maximumCompressionRatio,
        int maximumNameUtf8Bytes,
        int maximumDirectoryDepth,
        Duration maximumProcessingTime
) {
    public static final int DEFAULT_MAXIMUM_ENTRIES = 256;
    public static final long DEFAULT_MAXIMUM_ENTRY_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;
    public static final long DEFAULT_MAXIMUM_TOTAL_UNCOMPRESSED_BYTES = 256L * 1024 * 1024;
    public static final double DEFAULT_MAXIMUM_COMPRESSION_RATIO = 100.0d;
    public static final int DEFAULT_MAXIMUM_NAME_UTF8_BYTES = 1024;
    public static final int DEFAULT_MAXIMUM_DIRECTORY_DEPTH = 16;
    public static final Duration DEFAULT_MAXIMUM_PROCESSING_TIME = Duration.ofSeconds(30);

    public ArchiveSafetyLimits {
        if (maximumEntries < 1
                || maximumEntryUncompressedBytes < 1
                || maximumTotalUncompressedBytes < maximumEntryUncompressedBytes
                || !Double.isFinite(maximumCompressionRatio)
                || maximumCompressionRatio < 1.0d
                || maximumNameUtf8Bytes < 1
                || maximumNameUtf8Bytes > 1024
                || maximumDirectoryDepth < 0
                || maximumProcessingTime == null
                || maximumProcessingTime.isZero()
                || maximumProcessingTime.isNegative()) {
            throw new IllegalArgumentException("Archive safety limits are invalid");
        }
    }

    public static ArchiveSafetyLimits secureDefaults() {
        return new ArchiveSafetyLimits(
                DEFAULT_MAXIMUM_ENTRIES,
                DEFAULT_MAXIMUM_ENTRY_UNCOMPRESSED_BYTES,
                DEFAULT_MAXIMUM_TOTAL_UNCOMPRESSED_BYTES,
                DEFAULT_MAXIMUM_COMPRESSION_RATIO,
                DEFAULT_MAXIMUM_NAME_UTF8_BYTES,
                DEFAULT_MAXIMUM_DIRECTORY_DEPTH,
                DEFAULT_MAXIMUM_PROCESSING_TIME);
    }
}
