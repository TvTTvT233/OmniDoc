package com.javaee.aiservice.review.m0.model;

import java.text.Normalizer;
import java.util.Locale;

public record LogicalFileName(String original, String normalized) {
    public LogicalFileName(String original) {
        this(original, normalizeAndValidate(original));
    }

    public LogicalFileName {
        ModelChecks.required(original, "submissionFileName", 1024);
        if (!normalizeAndValidate(original).equals(normalized)) {
            throw ModelChecks.invalid("normalized submissionFileName is inconsistent");
        }
    }

    public void requireType(FormalFileType type) {
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(type.extension())) {
            throw ModelChecks.invalid("submissionFileName extension does not match the material type");
        }
    }

    private static String normalizeAndValidate(String value) {
        ModelChecks.required(value, "submissionFileName", 1024);
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        if (normalized.equals(".") || normalized.equals("..")
                || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0) {
            throw ModelChecks.invalid("submissionFileName must be a leaf file name");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (codePoint == 0 || Character.isISOControl(codePoint)) {
                throw ModelChecks.invalid("submissionFileName contains a control character");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }
}
