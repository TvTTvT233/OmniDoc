package com.javaee.aiservice.review.m0.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RemotePayloads {
    private static final ObjectMapper READER = new ObjectMapper();

    private RemotePayloads() {
    }

    static String segmentsJson(List<RemotePreviewSegment> segments) {
        return CanonicalJson.write(sorted(segments).stream().map(RemotePreviewSegment::storageValue).toList());
    }

    static List<RemotePreviewSegment> readSegments(String json) {
        try {
            JsonNode root = READER.readTree(json);
            if (!root.isArray()) {
                throw invalid("Stored remote preview is invalid");
            }
            List<RemotePreviewSegment> segments = new ArrayList<>();
            for (JsonNode node : root) {
                JsonNode locator = node.path("sourceLocator");
                segments.add(new RemotePreviewSegment(
                        text(node, "segmentId"), text(node, "basisId"),
                        new OfficialBasisSourceLocator(
                                nullableText(locator, "entryPath"), nullableInteger(locator, "page"),
                                nullableText(locator, "sheet"), nullableText(locator, "cellRange"),
                                nullableText(locator, "block"), nullableText(locator, "entryDigest")),
                        booleanValue(node, "included"), text(node, "redactedText")));
            }
            if (!segmentsJson(segments).equals(json)) {
                throw invalid("Stored remote preview is not canonical");
            }
            return List.copyOf(segments);
        } catch (ReviewM0Exception error) {
            throw error;
        } catch (Exception error) {
            throw invalid("Stored remote preview cannot be read");
        }
    }

    static byte[] payloadBytes(List<RemotePreviewSegment> segments) {
        List<Map<String, Object>> included = sorted(segments).stream()
                .filter(RemotePreviewSegment::included)
                .map(segment -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("segmentId", segment.segmentId());
                    value.put("text", segment.redactedText());
                    return value;
                }).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("segments", included);
        return CanonicalJson.write(payload).getBytes(StandardCharsets.UTF_8);
    }

    static String payloadDigest(List<RemotePreviewSegment> segments) {
        return sha256(payloadBytes(segments));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static List<RemotePreviewSegment> sorted(List<RemotePreviewSegment> segments) {
        return List.copyOf(segments).stream()
                .sorted(Comparator.comparing(RemotePreviewSegment::segmentId)).toList();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("Stored remote preview is incomplete");
        }
        return value.textValue();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("Stored remote source locator is invalid");
        }
        return value.textValue();
    }

    private static Integer nullableInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw invalid("Stored remote source locator is invalid");
        }
        return value.intValue();
    }

    private static boolean booleanValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid("Stored remote preview is incomplete");
        }
        return value.booleanValue();
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE, message);
    }
}
