package com.javaee.aiservice.review.m0.file;

import com.javaee.aiservice.review.m0.model.FormalFileType;

import java.util.Locale;
import java.util.Set;

public final class FormalContentTypes {
    private FormalContentTypes() {
    }

    public static boolean isCompatible(String declaredContentType, FormalFileType type) {
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return true;
        }
        String normalized = declaredContentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (normalized.equals("application/octet-stream")) {
            return true;
        }
        return expected(type).contains(normalized);
    }

    public static String responseContentType(FormalFileType type) {
        return switch (type) {
            case PDF -> "application/pdf";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case JPG -> "image/jpeg";
            case PNG -> "image/png";
            case ZIP -> "application/zip";
        };
    }

    private static Set<String> expected(FormalFileType type) {
        return switch (type) {
            case PDF -> Set.of("application/pdf");
            case DOCX -> Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip");
            case XLSX -> Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/zip");
            case JPG -> Set.of("image/jpeg", "image/jpg");
            case PNG -> Set.of("image/png");
            case ZIP -> Set.of("application/zip", "application/x-zip-compressed");
        };
    }
}
