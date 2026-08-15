package com.javaee.aiservice.review.m0.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.CanonicalJsonIntegrity;
import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.RequirementAuthorship;
import com.javaee.aiservice.review.m0.model.RequirementOrigin;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.UnicodeCodePointComparator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, strictly decoded view of a published checklist snapshot. */
public record FormalChecklist(
        String snapshotId,
        String checklistRevisionId,
        long basisRevision,
        long checklistRevision,
        String snapshotDigest,
        List<Item> items
) {
    public static final String CONTRACT_VERSION = "REVIEW_M0_CONTRACT_V1";
    private static final ObjectMapper READER = new ObjectMapper();

    public FormalChecklist {
        items = List.copyOf(items).stream()
                .sorted(Comparator.comparing(
                        Item::requirementId, UnicodeCodePointComparator.INSTANCE))
                .toList();
    }

    public static FormalChecklist from(ChecklistSnapshot snapshot) {
        if (snapshot.lifecycle() != ChecklistLifecycle.CONFIRMED || snapshot.supersededAt() != null) {
            throw conflict(ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED,
                    "Formal review requires the current published checklist snapshot");
        }
        CanonicalJsonIntegrity.requireValid(
                snapshot.snapshotJson(), snapshot.snapshotDigest(), "Checklist snapshot");
        try {
            JsonNode root = READER.readTree(snapshot.snapshotJson());
            requireText(root, "contractVersion", CONTRACT_VERSION);
            requireText(root, "caseId", snapshot.caseId());
            requireLong(root, "basisRevision", snapshot.basisRevision());
            requireLong(root, "checklistRevision", snapshot.checklistRevision());
            JsonNode itemNodes = root.get("items");
            if (itemNodes == null || !itemNodes.isArray()) {
                throw invalidStoredChecklist();
            }
            List<Item> items = new ArrayList<>();
            for (JsonNode node : itemNodes) {
                items.add(readItem(snapshot, node));
            }
            if (!CanonicalJson.write(READER.convertValue(root, Object.class)).equals(snapshot.snapshotJson())) {
                throw invalidStoredChecklist();
            }
            return new FormalChecklist(
                    snapshot.checklistSnapshotId(), snapshot.checklistRevisionId(), snapshot.basisRevision(),
                    snapshot.checklistRevision(), snapshot.snapshotDigest(), items);
        } catch (ReviewM0Exception error) {
            if (error.errorCode() == ReviewM0ErrorCode.REVIEW_M0_CONDITIONAL_UNRESOLVED) {
                throw error;
            }
            throw invalidStoredChecklist();
        } catch (Exception error) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                    "Published checklist snapshot cannot be decoded");
        }
    }

    private static Item readItem(ChecklistSnapshot snapshot, JsonNode node) {
        String requirementId = requiredText(node, "requirementId");
        boolean enabled = requiredBoolean(node, "enabled");
        RequirementApplicability applicability = enumValue(
                RequirementApplicability.class, requiredText(node, "applicability"));
        if (!enabled) {
            throw invalidStoredChecklist();
        }
        if (applicability == RequirementApplicability.NEEDS_USER_DECISION) {
            throw conflict(ReviewM0ErrorCode.REVIEW_M0_CONDITIONAL_UNRESOLVED,
                    "Published checklist contains an unresolved conditional item");
        }
        int minimumCount = requiredInt(node, "minimumCount");
        if (minimumCount < 1) {
            throw invalidStoredChecklist();
        }
        JsonNode ruleNode = node.get("fileNameRule");
        if (ruleNode == null || !ruleNode.isObject()) {
            throw invalidStoredChecklist();
        }
        FileNameRuleKind ruleKind = enumValue(
                FileNameRuleKind.class, requiredText(ruleNode, "kind"));
        String pattern = nullableText(ruleNode, "pattern");
        Boolean caseSensitive = nullableBoolean(ruleNode, "caseSensitive");
        FileNameRule rule = new FileNameRule(ruleKind, pattern, caseSensitive);
        RequirementOrigin origin = enumValue(
                RequirementOrigin.class, requiredText(node, "origin"));
        RequirementAuthorship authorship = enumValue(
                RequirementAuthorship.class, requiredText(node, "authorship"));
        long itemVersion = requiredLong(node, "itemVersion");
        JsonNode refsNode = node.get("sourceRefs");
        if (refsNode == null || !refsNode.isArray()) {
            throw invalidStoredChecklist();
        }
        List<SourceRef> sourceRefs = new ArrayList<>();
        for (JsonNode refNode : refsNode) {
            JsonNode locator = refNode.get("sourceLocator");
            if (locator == null || !locator.isObject()) {
                throw invalidStoredChecklist();
            }
            OfficialBasisSourceLocator sourceLocator = new OfficialBasisSourceLocator(
                    nullableText(locator, "entryPath"), nullableInteger(locator, "page"),
                    nullableText(locator, "sheet"), nullableText(locator, "cellRange"),
                    nullableText(locator, "block"), nullableText(locator, "entryDigest"));
            if (!sourceLocator.hasReviewableLocation()) {
                throw invalidStoredChecklist();
            }
            sourceRefs.add(new SourceRef(requiredText(refNode, "basisId"), sourceLocator));
        }
        if (origin == RequirementOrigin.OFFICIAL_EXTRACTED && sourceRefs.isEmpty()
                || origin == RequirementOrigin.USER_ADDED && !sourceRefs.isEmpty()) {
            throw invalidStoredChecklist();
        }
        return new Item(
                requirementId, applicability, minimumCount, rule, origin, authorship,
                itemVersion,
                snapshot.checklistRevisionId() + ":" + requirementId + ":" + itemVersion,
                sourceRefs);
    }

    public record Item(
            String requirementId,
            RequirementApplicability applicability,
            int minimumCount,
            FileNameRule fileNameRule,
            RequirementOrigin origin,
            RequirementAuthorship authorship,
            long itemVersion,
            String ruleRevision,
            List<SourceRef> sourceRefs
    ) {
        public Item {
            sourceRefs = List.copyOf(sourceRefs).stream()
                    .sorted(Comparator.comparing(
                            SourceRef::stableKey, UnicodeCodePointComparator.INSTANCE))
                    .toList();
        }
    }

    /** Source locator only. The official excerpt is intentionally not copied into review metadata. */
    public record SourceRef(String basisId, OfficialBasisSourceLocator locator) {
        public String stableKey() {
            return basisId + ":" + CanonicalJson.sha256(canonicalValue());
        }

        public Map<String, Object> canonicalValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("basisId", basisId);
            value.put("entryPath", locator.entryPath());
            value.put("page", locator.page());
            value.put("sheet", locator.sheet());
            value.put("cellRange", locator.cellRange());
            value.put("block", locator.block());
            value.put("entryDigest", locator.entryDigest());
            return value;
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidStoredChecklist();
        }
        return value.textValue();
    }

    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(requiredText(node, field))) {
            throw invalidStoredChecklist();
        }
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalidStoredChecklist();
        }
        return value.booleanValue();
    }

    private static Boolean nullableBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw invalidStoredChecklist();
        }
        return value.booleanValue();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw invalidStoredChecklist();
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw invalidStoredChecklist();
        }
        return value.longValue();
    }

    private static void requireLong(JsonNode node, String field, long expected) {
        if (requiredLong(node, field) != expected) {
            throw invalidStoredChecklist();
        }
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalidStoredChecklist();
        }
        return value.textValue();
    }

    private static Integer nullableInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw invalidStoredChecklist();
        }
        return value.intValue();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw invalidStoredChecklist();
        }
    }

    private static ReviewM0Exception invalidStoredChecklist() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                "Published checklist snapshot is invalid");
    }

    private static ReviewM0Exception conflict(ReviewM0ErrorCode code, String message) {
        return new ReviewM0Exception(code, message);
    }
}
