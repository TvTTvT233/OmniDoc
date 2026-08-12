package com.javaee.aiservice.review.m0.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class M001CapabilityMatrixTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void matrixUsesOnlyFrozenBaselineStatusesAndDoesNotClaimExternalVerification() throws Exception {
        JsonNode matrix = matrix();

        assertThat(matrix.path("classification").asText())
                .isEqualTo("TEST_ONLY_CURRENT_BEHAVIOR_BASELINE");
        assertThat(matrix.path("startCommit").asText())
                .isEqualTo("2157ab7d530c8a65996682fc63b1016bef517d07");
        assertThat(matrix.path("productionCodeChanged").asBoolean()).isFalse();
        assertThat(matrix.path("usesRealExternalDependencies").asBoolean()).isFalse();

        Set<String> ids = new HashSet<>();
        for (JsonNode capability : matrix.path("capabilities")) {
            assertThat(ids.add(capability.path("id").asText())).isTrue();
            assertThat(capability.path("currentStatus").asText())
                    .isIn("CURRENT_VERIFIED", "CURRENT_GAP");
            assertThat(capability.path("owner").asText()).isNotBlank();
            assertThat(capability.path("evidence").asText()).isNotBlank();
            if (!capability.path("targetStatus").isNull()) {
                assertThat(capability.path("targetStatus").asText())
                        .isEqualTo("PENDING_IMPLEMENTATION");
            }
        }
        assertThat(ids).contains(
                "FILE_OWNER_AUTHORIZATION",
                "FILE_ARTIFACT_SNAPSHOT_SHA256",
                "ZIP_SAFE_SINGLE_ENTRY_READ",
                "DOCUMENT_PDF_DOCX_CURRENT_BEHAVIOR",
                "DOCUMENT_XLSX_TYPED_EXTRACTION",
                "AI_JDBC_H2_MYSQL_MODE",
                "AI_REAL_MYSQL_STARTUP",
                "AI_AUTHENTICATED_USER_ID",
                "AI_STRUCTURED_HTTP_ERRORS",
                "AI_APPLICATION_CONTEXT_WITH_EXTERNALS_DISABLED");
    }

    @Test
    void everyPendingImplementationHasAnExplicitDownstreamOwner() throws Exception {
        JsonNode capabilities = matrix().path("capabilities");
        assertThat(StreamSupport.stream(capabilities.spliterator(), false)
                .filter(capability -> "PENDING_IMPLEMENTATION".equals(
                        capability.path("targetStatus").asText()))
                .allMatch(capability -> capability.path("owner").asText().startsWith("M0-")))
                .isTrue();
    }

    private static JsonNode matrix() throws Exception {
        try (InputStream input = M001CapabilityMatrixTest.class.getClassLoader()
                .getResourceAsStream("review-m0/baseline/m0-01-capability-matrix.json")) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
