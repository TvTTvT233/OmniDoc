package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.RequirementAuthorship;
import com.javaee.aiservice.review.m0.model.RequirementOrigin;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ChecklistDraftItem(
        String requirementId,
        String displayName,
        boolean enabled,
        RequirementApplicability applicability,
        int minimumCount,
        FileNameRule fileNameRule,
        RequirementOrigin origin,
        RequirementAuthorship authorship,
        List<ChecklistSourceRef> sourceRefs,
        long itemVersion
) {
    public ChecklistDraftItem {
        if (requirementId == null || requirementId.isBlank() || requirementId.length() > 64) {
            throw invalid("Checklist requirementId is invalid");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 1024) {
            throw invalid("Checklist displayName is invalid");
        }
        if (applicability == null || fileNameRule == null || origin == null || authorship == null) {
            throw invalid("Checklist item fields are incomplete");
        }
        if (minimumCount < 1 || itemVersion < 0) {
            throw invalid("Checklist item count or version is invalid");
        }
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        if (origin == RequirementOrigin.USER_ADDED && authorship != RequirementAuthorship.USER_REWRITTEN) {
            throw invalid("User-added requirements must retain USER_REWRITTEN authorship");
        }
        if (origin == RequirementOrigin.USER_ADDED && !sourceRefs.isEmpty()) {
            throw invalid("User-added requirements cannot impersonate official source references");
        }
        if (authorship == RequirementAuthorship.EXTRACTED_TEXT
                && origin != RequirementOrigin.OFFICIAL_EXTRACTED) {
            throw invalid("Only official extraction can have EXTRACTED_TEXT authorship");
        }
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("kind", fileNameRule.kind());
        rule.put("pattern", fileNameRule.pattern());
        rule.put("caseSensitive", fileNameRule.caseSensitive());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requirementId", requirementId);
        value.put("displayName", displayName);
        value.put("enabled", enabled);
        value.put("applicability", applicability);
        value.put("minimumCount", minimumCount);
        value.put("fileNameRule", rule);
        value.put("origin", origin);
        value.put("authorship", authorship);
        value.put("itemVersion", itemVersion);
        value.put("sourceRefs", sourceRefs.stream().map(ChecklistSourceRef::canonicalValue).toList());
        return value;
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }
}
