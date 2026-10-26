package com.javaee.aiservice.review.m0.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReviewJobRequestEnvelope(FormalReviewRequest request, ReviewInputManifest input) {
    private static final ObjectMapper READER = new ObjectMapper();

    public String canonicalJson() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("baseReportId", request.baseReportId());
        value.put("changedMaterialIds", request.changedMaterialIds());
        value.put("checklistSnapshotId", request.checklistSnapshotId());
        value.put("input", input.canonicalValue());
        value.put("mode", request.mode());
        value.put("uploadConfirmationId", request.uploadConfirmationId());
        return CanonicalJson.write(value);
    }

    public String digest() {
        return CanonicalJson.sha256(canonicalValue());
    }

    private Map<String, Object> canonicalValue() {
        try {
            return READER.convertValue(READER.readTree(canonicalJson()), Map.class);
        } catch (Exception error) {
            throw invalid();
        }
    }

    public static ReviewJobRequestEnvelope readCanonical(String json) {
        try {
            JsonNode root = READER.readTree(json);
            if (root == null || !root.isObject() || root.size() != 6) throw invalid();
            ReviewMode mode = ReviewMode.valueOf(requiredText(root, "mode"));
            String checklistSnapshotId = requiredText(root, "checklistSnapshotId");
            String uploadConfirmationId = requiredText(root, "uploadConfirmationId");
            String baseReportId = nullableText(root, "baseReportId");
            JsonNode changed = root.get("changedMaterialIds");
            if (changed == null || !changed.isArray()) throw invalid();
            List<String> changedIds = new ArrayList<>();
            for (JsonNode item : changed) {
                if (!item.isTextual()) throw invalid();
                changedIds.add(item.textValue());
            }
            JsonNode inputNode = root.get("input");
            if (inputNode == null || !inputNode.isObject()) throw invalid();
            ReviewInputManifest input = ReviewInputManifest.readCanonical(
                    CanonicalJson.write(READER.convertValue(inputNode, Object.class)));
            FormalReviewRequest request = new FormalReviewRequest(
                    mode, checklistSnapshotId, uploadConfirmationId, baseReportId, changedIds);
            ReviewJobRequestEnvelope envelope = new ReviewJobRequestEnvelope(request, input);
            if (!envelope.canonicalJson().equals(json)) throw invalid();
            return envelope;
        } catch (ReviewM0Exception error) {
            throw error;
        } catch (Exception error) {
            throw invalid();
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static ReviewM0Exception invalid() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                "Stored formal review Job request is invalid");
    }
}
