package com.javaee.aiservice.review.m0.baseline;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CurrentDocumentParserBaselineTest {

    @TempDir
    static Path temporaryDirectory;

    private ProductionDocumentParserHarness parser;

    @BeforeAll
    void compileAndLoadCheckedInProductionParser() throws Exception {
        parser = ProductionDocumentParserHarness.compileFromProductionSource(
                temporaryDirectory.resolve("parser-classes"));
    }

    @AfterAll
    void closeParserClassLoader() throws Exception {
        if (parser != null) {
            parser.close();
        }
    }

    @Test
    void productionParserRecognizesOnlyFourOfTheSixFormalM0Formats() throws Exception {
        assertThat(parser.supports("fixture.pdf")).isTrue();
        assertThat(parser.supports("fixture.docx")).isTrue();
        assertThat(parser.supports("fixture.xlsx")).isFalse();
        assertThat(parser.supports("fixture.jpg")).isTrue();
        assertThat(parser.supports("fixture.png")).isTrue();
        assertThat(parser.supports("fixture.zip")).isFalse();
    }

    @Test
    void pdfTextIsExtractedButPageIdentityIsFlattenedAway() throws Exception {
        String extracted = parser.parse(SyntheticReviewFixtureFactory.twoPagePdf(), "fixture.pdf");

        assertThat(normalizeWhitespace(extracted)).isEqualTo("PDF_PAGE_ONE PDF_PAGE_TWO");
        assertThat(extracted).doesNotContain("\f", "pageNumber", "pageIndex", "boundingBox");
    }

    @Test
    void docxParagraphAndTableTextIsRetainedButBodyOrderAndCoordinatesAreLost() throws Exception {
        String extracted = parser.parse(SyntheticReviewFixtureFactory.interleavedDocx(), "fixture.docx");

        assertThat(extracted).contains(
                "DOCX_PARAGRAPH_BEFORE",
                "DOCX_PARAGRAPH_AFTER",
                "DOCX_CELL_A1",
                "DOCX_CELL_B1",
                "DOCX_CELL_A2",
                "DOCX_CELL_B2");
        assertThat(extracted.indexOf("DOCX_PARAGRAPH_AFTER"))
                .as("production loops over all top-level paragraphs before all tables")
                .isLessThan(extracted.indexOf("DOCX_CELL_A1"));
        assertThat(extracted).doesNotContain("paragraphIndex", "tableIndex", "rowIndex", "cellIndex");
    }

    @Test
    void xlsxFallsBackToUtf8DecodingOfRawOoxmlBytes() throws Exception {
        byte[] fixture = SyntheticReviewFixtureFactory.oneCellXlsx();

        assertThat(parser.readXlsxCellWithAvailablePoi(fixture, "Evidence", 2, 1))
                .as("the synthetic XLSX is valid and POI is already available to document-service")
                .isEqualTo("XLSX_EVIDENCE_VALUE");
        assertThat(parser.supports("fixture.xlsx")).isFalse();
        assertThat(parser.parse(fixture, "fixture.xlsx"))
                .isEqualTo(new String(fixture, StandardCharsets.UTF_8).trim())
                .doesNotContain("XLSX_EVIDENCE_VALUE");
    }

    @Test
    void zipBundlesAreNotEnumeratedOrValidatedByTheDocumentParser() throws Exception {
        byte[] safe = SyntheticReviewFixtureFactory.safeZip();
        byte[] traversal = SyntheticReviewFixtureFactory.traversalZip();
        byte[] highCompression = SyntheticReviewFixtureFactory.highCompressionZip();

        assertThat(zipEntryNames(safe)).containsExactly("safe/evidence.txt");
        assertThat(zipEntryNames(traversal))
                .containsExactly("../outside.txt", "/absolute.txt", "C:/drive-root.txt");
        assertThat(64 * 1024L / highCompression.length)
                .as("small deterministic compression-bomb boundary ratio")
                .isGreaterThan(100L);
        assertThat(parser.supports("fixture.zip")).isFalse();
        assertThat(parser.parse(safe, "safe.zip"))
                .isEqualTo(new String(safe, StandardCharsets.UTF_8).trim());
        assertThat(parser.parse(traversal, "traversal.zip"))
                .isEqualTo(new String(traversal, StandardCharsets.UTF_8).trim());
        assertThat(parser.parse(highCompression, "high-compression.zip"))
                .isEqualTo(new String(highCompression, StandardCharsets.UTF_8).trim());
    }

    @Test
    void malformedRecognizedFormatIsSilentlyReturnedAsRawUtf8InsteadOfClassified() throws Exception {
        byte[] malformed = "NOT_A_REAL_PDF".getBytes(StandardCharsets.UTF_8);

        assertThat(parser.parse(malformed, "malformed.pdf")).isEqualTo("NOT_A_REAL_PDF");
        assertThat(parser.parse(new byte[0], "empty.pdf")).isEmpty();
    }

    @Test
    void imageFixturesAreValidAndProductionOcrReturnsTextOnlyWithoutBlocksOrConfidence() throws Exception {
        byte[] jpg = SyntheticReviewFixtureFactory.jpegWithText();
        byte[] png = SyntheticReviewFixtureFactory.pngWithText();
        assertThat(ImageIO.read(new ByteArrayInputStream(jpg))).isNotNull();
        assertThat(ImageIO.read(new ByteArrayInputStream(png))).isNotNull();

        String source = productionParserSource();
        assertThat(source)
                .contains("ITesseract tesseract = new Tesseract()")
                .contains("tesseract.setLanguage(\"eng+chi_sim\")")
                .contains("String result = tesseract.doOCR(image)");
        assertThat(source.toLowerCase(Locale.ROOT))
                .doesNotContain("getwords", "confidence", "boundingbox", "bounding_box");
    }

    @Test
    void noProductionArchiveExtractorOrZipSafetyLimitsExist() throws Exception {
        Path root = ProductionDocumentParserHarness.repositoryRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> productionJava = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
            String productionSource = productionJava.stream()
                    .map(CurrentDocumentParserBaselineTest::readUnchecked)
                    .reduce("", (left, right) -> left + "\n" + right);

            assertThat(productionSource)
                    .doesNotContain(
                            "ZipInputStream",
                            "java.util.zip.ZipFile",
                            "ZipArchiveInputStream",
                            "getCompressedSize()",
                            "archiveMaxEntries",
                            "archiveMaxDepth");
        }
    }

    @Disabled("Boundary only: requires native Tesseract plus eng and chi_sim traineddata; synthetic JPG is not a real scan")
    @Test
    void realJpgOcrAndScannedDocumentAccuracyRemainUnverified() throws Exception {
        assertThat(parser.parse(SyntheticReviewFixtureFactory.jpegWithText(), "fixture.jpg"))
                .containsIgnoringCase("JPG");
    }

    @Disabled("Boundary only: requires native Tesseract plus eng and chi_sim traineddata; synthetic PNG is not a real scan")
    @Test
    void realPngOcrAndConfidenceRemainUnverified() throws Exception {
        assertThat(parser.parse(SyntheticReviewFixtureFactory.pngWithText(), "fixture.png"))
                .containsIgnoringCase("PNG");
    }

    private String productionParserSource() throws Exception {
        Path root = ProductionDocumentParserHarness.repositoryRoot();
        return Files.readString(root.resolve(
                "document-service/src/main/java/com/javaee/documentservice/util/DocumentParserUtil.java"));
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static List<String> zipEntryNames(byte[] content) throws Exception {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read " + path, exception);
        }
    }
}
