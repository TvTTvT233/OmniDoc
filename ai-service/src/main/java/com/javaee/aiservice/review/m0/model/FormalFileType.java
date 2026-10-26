package com.javaee.aiservice.review.m0.model;

import java.util.Locale;
import java.util.Optional;

public enum FormalFileType {
    PDF(".pdf"), DOCX(".docx"), XLSX(".xlsx"), JPG(".jpg"), PNG(".png"), ZIP(".zip");

    private final String extension;

    FormalFileType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public boolean isLeafType() {
        return this != ZIP;
    }

    public static Optional<FormalFileType> fromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (FormalFileType type : values()) {
            if (lower.endsWith(type.extension)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
