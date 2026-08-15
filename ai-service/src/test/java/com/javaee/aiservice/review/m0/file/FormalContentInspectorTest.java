package com.javaee.aiservice.review.m0.file;

import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FormalContentInspectorTest {
    private final FormalContentInspector inspector =
            new FormalContentInspector(ArchiveSafetyLimits.secureDefaults());

    @Test
    void fiveTopLevelLeafTypesHaveStableSha256AndSize() throws Exception {
        assertContent(FormalFileType.PDF, SyntheticFileFixtures.pdf("pdf"));
        assertContent(FormalFileType.DOCX, SyntheticFileFixtures.docx("docx"));
        assertContent(FormalFileType.XLSX, SyntheticFileFixtures.xlsx("xlsx"));
        assertContent(FormalFileType.JPG, SyntheticFileFixtures.jpg((byte) 4));
        assertContent(FormalFileType.PNG, SyntheticFileFixtures.png((byte) 5));
    }

    @Test
    void pdfEncryptionIsCannotConfirmEncrypted() {
        assertFailure(FormalFileType.PDF, SyntheticFileFixtures.encryptedPdf(),
                CannotConfirmReason.ENCRYPTED);
    }

    @Test
    void encryptedOfficeOleContainerIsCannotConfirmEncrypted() {
        byte[] ole = new byte[]{
                (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1, 0, 0
        };
        assertFailure(FormalFileType.DOCX, ole, CannotConfirmReason.ENCRYPTED);
        assertFailure(FormalFileType.XLSX, ole, CannotConfirmReason.ENCRYPTED);
    }

    @Test
    void signatureMismatchAndRecognizableTruncationStayDistinct() {
        assertFailure(FormalFileType.PDF, SyntheticFileFixtures.png((byte) 1),
                CannotConfirmReason.TYPE_MISMATCH);
        assertFailure(FormalFileType.PDF, "%PDF-1.4\nno trailer".getBytes(),
                CannotConfirmReason.CORRUPTED);
        assertFailure(FormalFileType.JPG, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1},
                CannotConfirmReason.CORRUPTED);
        assertFailure(FormalFileType.PNG,
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a},
                CannotConfirmReason.CORRUPTED);
    }

    @Test
    void ooxmlMainPartMismatchIsTypeMismatch() {
        byte[] notDocx = SyntheticFileFixtures.zip(List.of(
                SyntheticFileFixtures.entry("[Content_Types].xml", "<Types/>".getBytes()),
                SyntheticFileFixtures.entry("xl/workbook.xml", "<workbook/>".getBytes())));
        assertFailure(FormalFileType.DOCX, notDocx, CannotConfirmReason.TYPE_MISMATCH);
    }

    private void assertContent(FormalFileType type, byte[] bytes) throws Exception {
        ContentInspection result = inspector.inspect(type, new ByteArrayInputStream(bytes));
        assertEquals(bytes.length, result.sizeBytes());
        assertEquals(SyntheticFileFixtures.sha256(bytes), result.contentDigest());
    }

    private void assertFailure(FormalFileType type, byte[] bytes, CannotConfirmReason reason) {
        ContentInspectionException error = assertThrows(ContentInspectionException.class,
                () -> inspector.inspect(type, new ByteArrayInputStream(bytes)));
        assertEquals(reason, error.reason());
    }
}
