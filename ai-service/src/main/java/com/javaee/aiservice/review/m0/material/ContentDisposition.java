package com.javaee.aiservice.review.m0.material;

import java.nio.charset.StandardCharsets;

public final class ContentDisposition {
    private ContentDisposition() {
    }

    public static String attachment(String normalizedFileName) {
        String fallback = asciiFallback(normalizedFileName);
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''"
                + rfc5987(normalizedFileName);
    }

    public static String asciiFallback(String value) {
        StringBuilder output = new StringBuilder(Math.min(value.length(), 180));
        for (int offset = 0; offset < value.length() && output.length() < 180;) {
            int codePoint = value.codePointAt(offset);
            if (codePoint < 128 && (Character.isLetterOrDigit(codePoint)
                    || codePoint == '.' || codePoint == '-' || codePoint == '_')) {
                output.append((char) codePoint);
            } else {
                output.append('_');
            }
            offset += Character.charCount(codePoint);
        }
        String fallback = output.toString();
        if (fallback.isBlank() || fallback.equals(".") || fallback.equals("..")) {
            return "download";
        }
        return fallback;
    }

    public static String rfc5987(String value) {
        StringBuilder output = new StringBuilder();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = item & 0xff;
            if (isAttrChar(unsigned)) {
                output.append((char) unsigned);
            } else {
                output.append('%');
                output.append(Character.toUpperCase(Character.forDigit((unsigned >>> 4) & 0xf, 16)));
                output.append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
            }
        }
        return output.toString();
    }

    private static boolean isAttrChar(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '!' || value == '#' || value == '$' || value == '&'
                || value == '+' || value == '-' || value == '.' || value == '^'
                || value == '_' || value == '`' || value == '|' || value == '~';
    }
}
