package com.javaee.fileservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Operational safety bounds for owner-aware download prevalidation. */
@Component
@ConfigurationProperties(prefix = "file.owned-download")
public class OwnedFileDownloadProperties {
    static final long ABSOLUTE_MAXIMUM_BYTES = 1024L * 1024L * 1024L;

    private Path stagingDirectory = Path.of(
            System.getProperty("java.io.tmpdir"), "omnidoc-owned-download-staging");
    private long maximumBytes = ABSOLUTE_MAXIMUM_BYTES;

    public Path getStagingDirectory() {
        return stagingDirectory;
    }

    public void setStagingDirectory(Path stagingDirectory) {
        this.stagingDirectory = stagingDirectory;
    }

    public long getMaximumBytes() {
        return maximumBytes;
    }

    public void setMaximumBytes(long maximumBytes) {
        this.maximumBytes = maximumBytes;
    }

    public Path requiredStagingDirectory() {
        if (stagingDirectory == null) {
            throw new IllegalStateException("Owned file download staging directory is required");
        }
        Path normalized = stagingDirectory.toAbsolutePath().normalize();
        Path systemTemporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize();
        if (normalized.getParent() == null || normalized.equals(systemTemporaryDirectory)) {
            throw new IllegalStateException("Owned file download staging requires a dedicated directory");
        }
        return normalized;
    }

    public long requiredMaximumBytes() {
        if (maximumBytes <= 0 || maximumBytes > ABSOLUTE_MAXIMUM_BYTES) {
            throw new IllegalStateException("Owned file download maximum bytes is outside its safety bound");
        }
        return maximumBytes;
    }
}
