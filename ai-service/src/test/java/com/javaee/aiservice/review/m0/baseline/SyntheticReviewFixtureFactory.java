package com.javaee.aiservice.review.m0.baseline;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates tiny, deterministic, non-sensitive files for parser characterization.
 * The generated files contain synthetic labels only and are never written outside
 * the JUnit temporary directory.
 */
final class SyntheticReviewFixtureFactory {

    private static final java.nio.charset.Charset PDF_CHARSET = StandardCharsets.ISO_8859_1;

    private SyntheticReviewFixtureFactory() {
    }

    static byte[] twoPagePdf() {
        String pageOne = "BT /F1 12 Tf 72 720 Td (PDF_PAGE_ONE) Tj ET";
        String pageTwo = "BT /F1 12 Tf 72 720 Td (PDF_PAGE_TWO) Tj ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        + "/Resources << /Font << /F1 7 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        + "/Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>",
                streamObject(pageOne),
                streamObject(pageTwo),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        );

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.toString().getBytes(PDF_CHARSET).length);
            pdf.append(i + 1).append(" 0 obj\n")
                    .append(objects.get(i)).append("\nendobj\n");
        }
        int xrefOffset = pdf.toString().getBytes(PDF_CHARSET).length;
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n")
                .append("0000000000 65535 f \n");
        for (Integer offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1)
                .append(" /Root 1 0 R >>\nstartxref\n")
                .append(xrefOffset).append("\n%%EOF\n");
        return pdf.toString().getBytes(PDF_CHARSET);
    }

    static byte[] interleavedDocx() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """);
        entries.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """);
        entries.put("word/document.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>DOCX_PARAGRAPH_BEFORE</w:t></w:r></w:p>
                    <w:tbl>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>DOCX_CELL_A1</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>DOCX_CELL_B1</w:t></w:r></w:p></w:tc>
                      </w:tr>
                      <w:tr>
                        <w:tc><w:p><w:r><w:t>DOCX_CELL_A2</w:t></w:r></w:p></w:tc>
                        <w:tc><w:p><w:r><w:t>DOCX_CELL_B2</w:t></w:r></w:p></w:tc>
                      </w:tr>
                    </w:tbl>
                    <w:p><w:r><w:t>DOCX_PARAGRAPH_AFTER</w:t></w:r></w:p>
                    <w:sectPr/>
                  </w:body>
                </w:document>
                """);
        return zipUtf8(entries);
    }

    static byte[] oneCellXlsx() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
                """);
        entries.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """);
        entries.put("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Evidence" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """);
        entries.put("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>
                """);
        entries.put("xl/worksheets/sheet1.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="3"><c r="B3" t="inlineStr"><is><t>XLSX_EVIDENCE_VALUE</t></is></c></row></sheetData>
                </worksheet>
                """);
        return zipUtf8(entries);
    }

    static byte[] jpegWithText() throws IOException {
        return rasterWithText("jpg", BufferedImage.TYPE_INT_RGB, Color.WHITE, Color.BLACK, "JPG OCR FIXTURE");
    }

    static byte[] pngWithText() throws IOException {
        return rasterWithText("png", BufferedImage.TYPE_INT_ARGB, Color.WHITE, Color.BLACK, "PNG OCR FIXTURE");
    }

    static byte[] safeZip() throws IOException {
        return zipUtf8(Map.of("safe/evidence.txt", "ZIP_SAFE_EVIDENCE"));
    }

    static byte[] traversalZip() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("../outside.txt", "ZIP_TRAVERSAL_EVIDENCE");
        entries.put("/absolute.txt", "ZIP_ABSOLUTE_EVIDENCE");
        entries.put("C:/drive-root.txt", "ZIP_DRIVE_EVIDENCE");
        return zipUtf8(entries);
    }

    static byte[] highCompressionZip() throws IOException {
        return zipUtf8(Map.of("large.txt", "A".repeat(64 * 1024)));
    }

    private static String streamObject(String stream) {
        int length = stream.getBytes(PDF_CHARSET).length;
        return "<< /Length " + length + " >>\nstream\n" + stream + "\nendstream";
    }

    private static byte[] rasterWithText(
            String format,
            int imageType,
            Color background,
            Color foreground,
            String text
    ) throws IOException {
        BufferedImage image = new BufferedImage(420, 100, imageType);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(background);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(foreground);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            graphics.drawString(text, 16, 60);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("No ImageIO writer for " + format);
        }
        return output.toByteArray();
    }

    private static byte[] zipUtf8(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
