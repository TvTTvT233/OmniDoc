package com.javaee.aiservice.review.m0.archive;

import java.util.List;

public record ArchiveInspection(
        long archiveSizeBytes,
        String archiveContentDigest,
        List<ArchiveEntryArtifact> entries
) {
    public ArchiveInspection {
        entries = List.copyOf(entries);
    }
}
