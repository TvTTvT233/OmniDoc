package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.model.LogicalFileName;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentDispositionTest {
    @Test
    void unicodeNameUsesNfcSafeAsciiFallbackAndRfc5987Utf8() {
        LogicalFileName logical = new LogicalFileName("e\u0301-申请表.PDF");
        String header = ContentDisposition.attachment(logical.normalized());

        assertEquals(Normalizer.normalize("e\u0301-申请表.PDF", Normalizer.Form.NFC), logical.normalized());
        assertTrue(header.startsWith("attachment; filename=\""));
        assertTrue(header.contains("filename*=UTF-8''%C3%A9-%E7%94%B3%E8%AF%B7%E8%A1%A8.PDF"));
        assertFalse(header.contains("\r"));
        assertFalse(header.contains("\n"));
    }

    @Test
    void quoteAndPercentCannotBreakFallbackOrExtendedParameter() {
        String header = ContentDisposition.attachment("a\"b%25.pdf");
        assertTrue(header.contains("filename=\"a_b_25.pdf\""));
        assertTrue(header.contains("filename*=UTF-8''a%22b%2525.pdf"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bad\rname.pdf",
            "bad\nname.pdf",
            "bad\u0000name.pdf",
            "bad\u0001name.pdf",
            "../name.pdf",
            "..\\name.pdf",
            ".",
            ".."
    })
    void unsafeLogicalNamesAreRejectedBeforeHeaderConstruction(String value) {
        assertThrows(ReviewM0Exception.class, () -> new LogicalFileName(value));
    }
}
