package com.javaee.aiservice.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSafeLoggingConfigurationTest {

    @Test
    void defaultAndLocalProfilesUseInfoSqlGuardsAndOptInAuxiliaryScheduling() throws IOException {
        for (String resource : new String[]{"application.yml", "application-local.yml"}) {
            String yaml = Files.readString(modulePath("src/main/resources").resolve(resource));
            assertThat(yaml)
                    .contains("exception-conversion-word: \"%nopex\"")
                    .contains("com.javaee.aiservice: info")
                    .contains("root: info")
                    .contains("org.springframework.jdbc.core.JdbcTemplate: warn")
                    .contains("org.springframework.jdbc.core.StatementCreatorUtils: warn")
                    .contains("org.springframework.jdbc.datasource.init.ScriptUtils: warn")
                    .contains("org.springframework.transaction: warn")
                    .contains("org.hibernate.SQL: off")
                    .contains("connection-timeout: ${AI_DB_CONNECTION_TIMEOUT_MS:5000}")
                    .contains("idempotency-total-wait-budget: ${AI_REVIEW_M0_IDEMPOTENCY_TOTAL_WAIT_BUDGET:8s}")
                    .contains("enabled: ${AI_AIOPS_SCHEDULING_ENABLED:false}")
                    .doesNotContain("com.javaee.aiservice: debug");
        }
    }

    @Test
    void productionSourcesDoNotLogThrowableObjectsMessagesOrPrintStacks() throws IOException {
        Path sourceRoot = modulePath("src/main/java/com/javaee/aiservice");
        Path commonRoot = modulePath("../common/src/main/java/com/javaee/common");
        if (!Files.exists(commonRoot)) {
            commonRoot = Path.of("common/src/main/java/com/javaee/common");
        }
        try (var aiPaths = Files.walk(sourceRoot); var commonPaths = Files.walk(commonRoot)) {
            String sources = java.util.stream.Stream.concat(aiPaths, commonPaths)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(RuntimeSafeLoggingConfigurationTest::read)
                    .reduce("", (left, right) -> left + "\n" + right);

            assertThat(sources)
                    .doesNotContain("printStackTrace(")
                    .doesNotContain("System.out.print", "System.err.print");
            assertThat(sources).doesNotMatch("(?s).*log\\.(?:warn|error)\\([^;]*getMessage\\(\\)[^;]*;.*");
            assertThat(sources).doesNotMatch(
                    "(?s).*log\\.(?:warn|error)\\([^;]*,\\s*(?:e|ex|error|exception|throwable)\\s*\\);.*");
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to inspect production logging sources", error);
        }
    }

    private static Path modulePath(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("ai-service").resolve(relative);
    }
}
