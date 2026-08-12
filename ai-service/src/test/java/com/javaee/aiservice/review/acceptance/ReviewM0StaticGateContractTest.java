package com.javaee.aiservice.review.acceptance;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0StaticGateContractTest {

    @Test
    void staticGateScansGitVisibleFilesAndDeclaredProductionRoots() throws Exception {
        String script = Files.readString(scriptPath());
        assertThat(script).contains(
                "git -C $RepositoryRoot ls-files --cached --others --exclude-standard",
                "Resolve-SafeRelativeRoot",
                "$StaticScanRoots",
                "[System.IO.Path]::IsPathRooted",
                "Static scan root escapes the repository");
    }

    @Test
    void staticGateRedactsFindingsAndChecksSecretsPiiReportsAndSensitiveLogging() throws Exception {
        String script = Files.readString(scriptPath());
        assertThat(script).contains(
                "OpenAI-style secret literal",
                "Private key material",
                "Email-like PII",
                "Mainland phone-like PII",
                "Mainland ID-like PII",
                "Forbidden top-level report field",
                "Sensitive Review M0 value in log statement",
                "redacted finding(s)");
        assertThat(script).doesNotContain("$match.Value");
    }

    private static Path scriptPath() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 4 && current != null; depth++, current = current.getParent()) {
            Path candidate = current.resolve("scripts/review-m0/assert-no-sensitive-data.ps1");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Unable to locate scripts/review-m0/assert-no-sensitive-data.ps1");
    }
}
