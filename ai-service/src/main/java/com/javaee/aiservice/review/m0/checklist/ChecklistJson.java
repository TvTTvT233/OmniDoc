package com.javaee.aiservice.review.m0.checklist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChecklistJson {
    private static final ObjectMapper READER = new ObjectMapper();

    private ChecklistJson() {
    }

    static String sourceRefs(List<ChecklistSourceRef> sourceRefs) {
        return CanonicalJson.write(sourceRefs.stream().map(ChecklistSourceRef::canonicalValue).toList());
    }

    static List<ChecklistSourceRef> readSourceRefs(String json) {
        try {
            JsonNode root = READER.readTree(json);
            if (!root.isArray()) {
            throw invalid("Stored checklist source references are invalid");
            }
            List<ChecklistSourceRef> refs = new ArrayList<>();
            for (JsonNode node : root) {
                JsonNode locator = node.path("sourceLocator");
                refs.add(new ChecklistSourceRef(
                        requiredText(node, "basisId"),
                        new OfficialBasisSourceLocator(
                                nullableText(locator, "entryPath"),
                                nullableInteger(locator, "page"),
                                nullableText(locator, "sheet"),
                                nullableText(locator, "cellRange"),
                                nullableText(locator, "block"),
                                nullableText(locator, "entryDigest")),
                        requiredText(node, "quotedText")));
            }
            if (!CanonicalJson.write(rootToValue(root)).equals(json)) {
                throw invalid("Stored checklist source references are not canonical");
            }
            return List.copyOf(refs);
        } catch (ReviewM0Exception error) {
            throw error;
        } catch (Exception error) {
            throw invalid("Stored checklist source references cannot be read");
        }
    }

    static String draftDigest(String caseId, long basisRevision, List<ChecklistDraftItem> items) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("caseId", caseId);
        value.put("basisRevision", basisRevision);
        value.put("items", sorted(items).stream().map(ChecklistDraftItem::canonicalValue).toList());
        return CanonicalJson.sha256(value);
    }

    static String snapshotJson(String caseId, long basisRevision, long checklistRevision,
                               List<ChecklistDraftItem> items) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", "REVIEW_M0_CONTRACT_V1");
        value.put("caseId", caseId);
        value.put("basisRevision", basisRevision);
        value.put("checklistRevision", checklistRevision);
        value.put("items", sorted(items).stream().map(ChecklistDraftItem::canonicalValue).toList());
        return CanonicalJson.write(value);
    }

    static List<ChecklistDraftItem> sorted(List<ChecklistDraftItem> items) {
        return items.stream().sorted(Comparator.comparing(ChecklistDraftItem::requirementId)).toList();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("Stored checklist source reference is incomplete");
        }
        return value.textValue();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("Stored source locator is invalid");
        }
        return value.textValue();
    }

    private static Integer nullableInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw invalid("Stored source locator is invalid");
        }
        return value.intValue();
    }

    @SuppressWarnings("unchecked")
    private static Object rootToValue(JsonNode root) {
        return READER.convertValue(root, Object.class);
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE, message);
    }
}
