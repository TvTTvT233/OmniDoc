package com.javaee.aiservice.review.m0.config;

import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Review-only typed limits. No common application YAML is required or modified. */
@ConfigurationProperties(prefix = "ai.review-m0.runtime")
public final class ReviewM0RuntimeProperties {
    private int maximumArchiveEntries = ArchiveSafetyLimits.DEFAULT_MAXIMUM_ENTRIES;
    private long maximumEntryUncompressedBytes = ArchiveSafetyLimits.DEFAULT_MAXIMUM_ENTRY_UNCOMPRESSED_BYTES;
    private long maximumTotalUncompressedBytes = ArchiveSafetyLimits.DEFAULT_MAXIMUM_TOTAL_UNCOMPRESSED_BYTES;
    private double maximumCompressionRatio = ArchiveSafetyLimits.DEFAULT_MAXIMUM_COMPRESSION_RATIO;
    private int maximumArchiveNameUtf8Bytes = ArchiveSafetyLimits.DEFAULT_MAXIMUM_NAME_UTF8_BYTES;
    private int maximumArchiveDirectoryDepth = ArchiveSafetyLimits.DEFAULT_MAXIMUM_DIRECTORY_DEPTH;
    private Duration maximumArchiveProcessingTime = ArchiveSafetyLimits.DEFAULT_MAXIMUM_PROCESSING_TIME;
    private Duration regexTimeout = Duration.ofMillis(100);

    public ArchiveSafetyLimits archiveSafetyLimits() {
        return new ArchiveSafetyLimits(
                maximumArchiveEntries, maximumEntryUncompressedBytes,
                maximumTotalUncompressedBytes, maximumCompressionRatio,
                maximumArchiveNameUtf8Bytes, maximumArchiveDirectoryDepth,
                maximumArchiveProcessingTime);
    }

    public Duration validatedRegexTimeout() {
        if (regexTimeout == null || regexTimeout.isZero() || regexTimeout.isNegative()
                || regexTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalStateException("Review M0 regex timeout is invalid");
        }
        return regexTimeout;
    }

    public int getMaximumArchiveEntries() { return maximumArchiveEntries; }
    public void setMaximumArchiveEntries(int value) { maximumArchiveEntries = value; }
    public long getMaximumEntryUncompressedBytes() { return maximumEntryUncompressedBytes; }
    public void setMaximumEntryUncompressedBytes(long value) { maximumEntryUncompressedBytes = value; }
    public long getMaximumTotalUncompressedBytes() { return maximumTotalUncompressedBytes; }
    public void setMaximumTotalUncompressedBytes(long value) { maximumTotalUncompressedBytes = value; }
    public double getMaximumCompressionRatio() { return maximumCompressionRatio; }
    public void setMaximumCompressionRatio(double value) { maximumCompressionRatio = value; }
    public int getMaximumArchiveNameUtf8Bytes() { return maximumArchiveNameUtf8Bytes; }
    public void setMaximumArchiveNameUtf8Bytes(int value) { maximumArchiveNameUtf8Bytes = value; }
    public int getMaximumArchiveDirectoryDepth() { return maximumArchiveDirectoryDepth; }
    public void setMaximumArchiveDirectoryDepth(int value) { maximumArchiveDirectoryDepth = value; }
    public Duration getMaximumArchiveProcessingTime() { return maximumArchiveProcessingTime; }
    public void setMaximumArchiveProcessingTime(Duration value) { maximumArchiveProcessingTime = value; }
    public Duration getRegexTimeout() { return regexTimeout; }
    public void setRegexTimeout(Duration value) { regexTimeout = value; }
}
