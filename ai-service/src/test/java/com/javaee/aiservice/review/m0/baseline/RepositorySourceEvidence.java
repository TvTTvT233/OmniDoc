package com.javaee.aiservice.review.m0.baseline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads checked-in production sources for baseline assertions without copying
 * production code into the Review M0 test boundary.
 */
final class RepositorySourceEvidence {

    private RepositorySourceEvidence() {
    }

    static Path repositoryRoot() {
        return ProductionDocumentParserHarness.repositoryRoot();
    }

    static String read(String repositoryRelativePath) {
        Path source = repositoryRoot().resolve(repositoryRelativePath).normalize();
        if (!source.startsWith(repositoryRoot())) {
            throw new IllegalArgumentException("Evidence path leaves the repository: " + repositoryRelativePath);
        }
        try {
            return Files.readString(source);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read baseline evidence " + source, exception);
        }
    }
}
