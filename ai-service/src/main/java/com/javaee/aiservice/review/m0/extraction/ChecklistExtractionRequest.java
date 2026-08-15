package com.javaee.aiservice.review.m0.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.util.LinkedHashMap;
import java.util.Map;

public record ChecklistExtractionRequest(
        long basisRevision,
        ProcessingMode processingMode,
        String consentId
) {
    private static final ObjectMapper READER = new ObjectMapper();

    public ChecklistExtractionRequest {
        if (basisRevision < 0 || processingMode == null
                || processingMode == ProcessingMode.LOCAL_ONLY && consentId != null
                || processingMode == ProcessingMode.REMOTE_MODEL
                && (consentId == null || consentId.isBlank() || consentId.length() > 64)) {
            throw invalid("Checklist extraction request is invalid");
        }
    }

    public String canonicalJson() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("basisRevision", basisRevision);
        value.put("processingMode", processingMode);
        value.put("consentId", consentId);
        return CanonicalJson.write(value);
    }

    public static ChecklistExtractionRequest read(String json) {
        try {
            JsonNode node = READER.readTree(json);
            if (!node.isObject() || node.size() != 3
                    || !node.path("basisRevision").canConvertToLong()
                    || !node.path("processingMode").isTextual()) {
                throw invalid("Checklist extraction request is invalid");
            }
            JsonNode consent = node.get("consentId");
            String consentId = consent == null || consent.isNull() ? null : consent.textValue();
            ChecklistExtractionRequest request = new ChecklistExtractionRequest(
                    node.path("basisRevision").longValue(),
                    ProcessingMode.valueOf(node.path("processingMode").textValue()), consentId);
            if (!request.canonicalJson().equals(json)) {
                throw invalid("Checklist extraction request is not canonical");
            }
            return request;
        } catch (ReviewM0Exception error) {
            throw error;
        } catch (Exception error) {
            throw invalid("Checklist extraction request cannot be read");
        }
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }
}
