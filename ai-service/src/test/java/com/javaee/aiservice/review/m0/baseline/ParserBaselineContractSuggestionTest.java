package com.javaee.aiservice.review.m0.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ParserBaselineContractSuggestionTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void fixtureManifestCoversEveryFormalM0FormatWithoutClaimingProductionReadiness() throws Exception {
        JsonNode manifest = resource("review-m0/baseline/parser-fixture-manifest.json");

        assertThat(manifest.path("classification").asText())
                .isEqualTo("TEST_ONLY_CURRENT_BEHAVIOR_BASELINE");
        assertThat(textValues(manifest.path("formats"), "format"))
                .containsExactlyInAnyOrder("PDF", "DOCX", "XLSX", "JPG", "PNG", "ZIP");
        assertThat(StreamSupport.stream(manifest.path("formats").spliterator(), false)
                .allMatch(node -> node.path("syntheticOnly").asBoolean())).isTrue();
        assertThat(manifest.path("productionModuleChanged").asBoolean()).isFalse();
    }

    @Test
    void sourceAnchorFieldsRemainExplicitlyNonFinalAndCoverFormatSpecificCoordinates() throws Exception {
        JsonNode contract = resource("review-m0/baseline/source-anchor-candidates.json");

        assertThat(contract.path("contractStatus").asText())
                .isEqualTo("NON_FINAL_SUGGESTION_FOR_M0_00");
        assertThat(contract.path("intendedPlacement").asText())
                .isEqualTo("M0_00_SOURCE_REFS_SOURCE_LOCATOR");
        assertThat(contract.path("publicApiContract").asBoolean()).isFalse();
        assertThat(textValues(contract.path("sourceAnchorCandidate").path("common")))
                .containsExactly(
                        "sourceId", "sourceVersion", "mediaType", "contentDigest",
                        "locatorKind", "exactText", "textDigest", "anchorQuality");
        assertThat(textValues(contract.path("sourceAnchorCandidate").path("pdf")))
                .contains("pageNumber", "boundingBox", "charStart", "charEnd");
        assertThat(textValues(contract.path("sourceAnchorCandidate").path("docx")))
                .contains("bodyElementIndex", "paragraphIndex", "tableIndex", "rowIndex", "cellIndex");
        assertThat(textValues(contract.path("sourceAnchorCandidate").path("xlsx")))
                .contains("sheetName", "sheetIndex", "cellReference", "rowIndex", "columnIndex");
        assertThat(textValues(contract.path("sourceAnchorCandidate").path("image")))
                .contains("blockIndex", "boundingBox", "ocrText", "confidence", "ocrEngine", "ocrLanguage");
        assertThat(textValues(contract.path("sourceAnchorCandidate").path("zip")))
                .contains("entryPath", "entryIndex", "entryDigest", "containerDepth", "parentArchiveAnchor");
    }

    @Test
    void proposedFailureCodesAreUniqueAndIncludeDependencyAndArchiveBoundaries() throws Exception {
        JsonNode contract = resource("review-m0/baseline/source-anchor-candidates.json");
        List<String> codes = textValues(contract.path("failureClassification"), "code");

        assertThat(new HashSet<>(codes)).hasSameSizeAs(codes);
        assertThat(codes).contains(
                "UNSUPPORTED_FORMAT",
                "MALFORMED_DOCUMENT",
                "ENCRYPTED_OR_PASSWORD_PROTECTED",
                "TEXT_NOT_EXTRACTABLE",
                "OCR_RUNTIME_UNAVAILABLE",
                "OCR_LANGUAGE_DATA_MISSING",
                "ANCHOR_UNAVAILABLE",
                "ARCHIVE_ENTRY_UNSAFE_PATH",
                "ARCHIVE_LIMIT_EXCEEDED",
                "ARCHIVE_NESTING_LIMIT_EXCEEDED",
                "PARTIAL_EXTRACTION");
        assertThat(StreamSupport.stream(contract.path("failureClassification").spliterator(), false)
                .allMatch(node -> !node.path("m000Disposition").asText().isBlank())).isTrue();
        assertThat(textValues(contract.path("anchorQuality")))
                .containsExactly("EXACT", "APPROXIMATE", "TEXT_ONLY", "UNAVAILABLE");
    }

    private static JsonNode resource(String path) throws Exception {
        try (InputStream input = ParserBaselineContractSuggestionTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static List<String> textValues(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private static List<String> textValues(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.path(field).asText())
                .toList();
    }
}
