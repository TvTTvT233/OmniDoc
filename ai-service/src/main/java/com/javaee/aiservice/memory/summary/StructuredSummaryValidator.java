package com.javaee.aiservice.memory.summary;

import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.ConversationRole;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class StructuredSummaryValidator {

    private static final Map<Category, Set<String>> STATUSES = Map.of(
            Category.GOAL, Set.of("active", "completed", "cancelled"),
            Category.CONSTRAINT, Set.of("active", "superseded"),
            Category.FACT, Set.of("confirmed", "uncertain", "conflicting", "superseded"),
            Category.DECISION, Set.of("proposed", "confirmed", "rejected", "superseded"),
            Category.OPEN_QUESTION, Set.of("open", "resolved", "dropped"),
            Category.TODO, Set.of("pending", "in_progress", "done", "cancelled"),
            Category.RESOURCE, Set.of("active", "superseded", "removed"));
    private static final Pattern EXPLICIT_CONFIRMATION = Pattern.compile(
            "(?i)(确认|同意|决定|就这样|按这个|\\bconfirm(?:ed)?\\b|\\bagree(?:d)?\\b|\\byes\\b)");
    private static final Pattern CONTENT_HASH = Pattern.compile("[a-f0-9]{64}");

    private final SummaryInputSanitizer sanitizer;

    public StructuredSummaryValidator(SummaryInputSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public void validate(StructuredConversationSummary summary, SummaryValidationContext expected) {
        require(summary != null, "Structured Summary is required");
        require("1.0".equals(summary.schemaVersion()), "Unsupported Summary schemaVersion");
        require(expected.conversationId().equals(summary.conversationId()), "Summary conversationId mismatch");
        require(summary.conversationId().length() <= 64, "Summary conversationId is too long");
        require(summary.summaryVersion() == expected.expectedSummaryVersion(), "Summary version mismatch");
        require(summary.coverage() != null, "Summary coverage is required");
        require(summary.coverage().fromSeq() == expected.expectedFromSequence(), "Summary fromSeq mismatch");
        require(summary.coverage().toSeq() == expected.expectedToSequence(), "Summary toSeq mismatch");
        require(expected.expectedSourceDigest().equals(summary.coverage().sourceDigest()),
                "Summary sourceDigest mismatch");
        require(summary.meta() != null, "Summary meta is required");
        require(summary.meta().previousSummaryVersion() == expected.expectedPreviousSummaryVersion(),
                "Summary previous version mismatch");
        require(expected.expectedGeneratedAt().equals(summary.meta().generatedAt()),
                "Summary generatedAt mismatch");
        SummarySourceDigest.requireContinuous(
                expected.expectedFromSequence(), expected.expectedToSequence(), expected.coveredMessages());

        Map<String, ConversationMessage> messages = new HashMap<>();
        expected.coveredMessages().forEach(message -> messages.put(message.messageId(), message));
        Set<String> previousDocumentRefs = previousDocumentRefs(expected.previousSummary());
        Set<String> allIds = new HashSet<>();

        validateCategory(Category.GOAL, summary.goal() == null ? List.of() : List.of(summary.goal()),
                expected, messages, previousDocumentRefs, allIds);
        validateCategory(Category.CONSTRAINT, summary.constraints(), expected, messages, previousDocumentRefs, allIds);
        validateCategory(Category.FACT, summary.facts(), expected, messages, previousDocumentRefs, allIds);
        validateCategory(Category.DECISION, summary.decisions(), expected, messages, previousDocumentRefs, allIds);
        validateCategory(Category.OPEN_QUESTION, summary.openQuestions(), expected, messages, previousDocumentRefs, allIds);
        validateCategory(Category.TODO, summary.todos(), expected, messages, previousDocumentRefs, allIds);
        validateCategory(Category.RESOURCE, summary.resources(), expected, messages, previousDocumentRefs, allIds);
        validatePreviousItemsRetained(summary, expected.previousSummary());
    }

    private void validateCategory(
            Category category,
            List<SummaryItem> items,
            SummaryValidationContext expected,
            Map<String, ConversationMessage> messages,
            Set<String> previousDocumentRefs,
            Set<String> allIds
    ) {
        require(items != null, "Summary arrays must not be null");
        for (SummaryItem item : items) {
            require(item != null, "Summary item must not be null");
            requireText(item.id(), 128, "Summary item id");
            require(allIds.add(item.id()), "Summary item ids must be globally unique");
            requireText(item.content(), 4000, "Summary item content");
            require(!sanitizer.containsSensitiveValue(item.content()), "Summary item contains a sensitive value");
            require(STATUSES.get(category).contains(item.status()), "Summary item status is invalid");
            require(item.lastUpdatedSeq() >= expected.expectedFromSequence()
                            && item.lastUpdatedSeq() <= expected.expectedToSequence(),
                    "Summary item lastUpdatedSeq is outside coverage");
            require(item.sourceRefs() != null && !item.sourceRefs().isEmpty(),
                    "Summary item requires at least one sourceRef");
            for (SummarySourceRef sourceRef : item.sourceRefs()) {
                validateSourceRef(sourceRef, messages, previousDocumentRefs);
            }
            enforceSourceSemantics(category, item, messages, expected.previousSummary());
            enforceTransition(category, item, expected.previousSummary());
        }
    }

    private void validateSourceRef(
            SummarySourceRef sourceRef,
            Map<String, ConversationMessage> messages,
            Set<String> previousDocumentRefs
    ) {
        require(sourceRef != null && sourceRef.type() != null, "Summary sourceRef type is required");
        requireText(sourceRef.id(), 128, "Summary sourceRef id");
        if (sourceRef.versionId() != null) {
            requireText(sourceRef.versionId(), 128, "Summary sourceRef versionId");
        }
        if (sourceRef.contentHash() != null) {
            require(CONTENT_HASH.matcher(sourceRef.contentHash()).matches(),
                    "Summary sourceRef contentHash is invalid");
        }
        require(!sanitizer.containsSensitiveValue(sourceRef.id())
                        && !sanitizer.containsSensitiveValue(sourceRef.versionId()),
                "Summary sourceRef identity contains a sensitive value");
        require(!sanitizer.containsSensitiveValue(String.valueOf(sourceRef.locator())),
                "Summary sourceRef locator contains a sensitive value");
        if (sourceRef.type() == SummarySourceType.DOCUMENT) {
            require(previousDocumentRefs.contains(sourceKey(sourceRef)),
                    "A new DOCUMENT sourceRef cannot be verified by Conversation Memory V1");
            return;
        }
        ConversationMessage message = messages.get(sourceRef.id());
        require(message != null, "Summary sourceRef does not point to a covered message");
        if (sourceRef.type() == SummarySourceType.TOOL_RESULT) {
            require(message.messageType() == ConversationMessageType.TOOL_RESULT,
                    "TOOL_RESULT sourceRef must point to a tool result message");
        }
        if (sourceRef.contentHash() != null) {
            require(sourceRef.contentHash().equals(message.contentHash()),
                    "Summary sourceRef contentHash mismatch");
        }
    }

    private void enforceSourceSemantics(
            Category category,
            SummaryItem item,
            Map<String, ConversationMessage> messages,
            StructuredConversationSummary previous
    ) {
        boolean previousSameStatus = previousItem(previous, category, item.id())
                .map(old -> old.status().equals(item.status()))
                .orElse(false);
        boolean hasUserSource = item.sourceRefs().stream()
                .map(ref -> messages.get(ref.id()))
                .anyMatch(message -> message != null && message.role() == ConversationRole.USER);
        boolean hasAuthoritativeSource = item.sourceRefs().stream().anyMatch(ref ->
                ref.type() == SummarySourceType.DOCUMENT
                        || (messages.get(ref.id()) != null && messages.get(ref.id()).role() == ConversationRole.USER));

        if ((category == Category.GOAL || category == Category.CONSTRAINT) && !previousSameStatus) {
            require(hasUserSource, "New goals and constraints require a USER message source");
        }
        if (category == Category.FACT && "confirmed".equals(item.status()) && !previousSameStatus) {
            require(hasAuthoritativeSource, "A confirmed fact requires a USER or verified DOCUMENT source");
        }
        if (category == Category.DECISION && "confirmed".equals(item.status()) && !previousSameStatus) {
            boolean explicitUserConfirmation = item.sourceRefs().stream()
                    .map(ref -> messages.get(ref.id()))
                    .anyMatch(message -> message != null
                            && message.role() == ConversationRole.USER
                            && EXPLICIT_CONFIRMATION.matcher(message.content()).find());
            require(explicitUserConfirmation,
                    "A confirmed decision requires an explicitly confirming USER message");
        }
    }

    private void enforceTransition(
            Category category,
            SummaryItem item,
            StructuredConversationSummary previous
    ) {
        previousItem(previous, category, item.id()).ifPresent(old -> {
            require(item.lastUpdatedSeq() >= old.lastUpdatedSeq(), "Summary item sequence moved backwards");
            require(allowedTransition(category, old.status(), item.status()), "Summary item status transition is invalid");
        });
    }

    private void validatePreviousItemsRetained(
            StructuredConversationSummary current,
            StructuredConversationSummary previous
    ) {
        if (previous == null) {
            return;
        }
        for (Category category : Category.values()) {
            Set<String> currentIds = items(current, category).stream().map(SummaryItem::id).collect(java.util.stream.Collectors.toSet());
            for (SummaryItem old : items(previous, category)) {
                require(currentIds.contains(old.id()), "A previous Summary item disappeared without a terminal state");
            }
        }
    }

    private static boolean allowedTransition(Category category, String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        return switch (category) {
            case GOAL -> from.equals("active") && Set.of("completed", "cancelled").contains(to);
            case CONSTRAINT -> from.equals("active") && to.equals("superseded");
            case FACT -> !from.equals("superseded") && Set.of("confirmed", "uncertain", "conflicting", "superseded").contains(to);
            case DECISION -> (from.equals("proposed") && Set.of("confirmed", "rejected", "superseded").contains(to))
                    || (from.equals("confirmed") && to.equals("superseded"))
                    || (from.equals("rejected") && to.equals("superseded"));
            case OPEN_QUESTION -> from.equals("open") && Set.of("resolved", "dropped").contains(to);
            case TODO -> (from.equals("pending") && Set.of("in_progress", "done", "cancelled").contains(to))
                    || (from.equals("in_progress") && Set.of("done", "cancelled").contains(to));
            case RESOURCE -> from.equals("active") && Set.of("superseded", "removed").contains(to);
        };
    }

    private static java.util.Optional<SummaryItem> previousItem(
            StructuredConversationSummary previous,
            Category category,
            String id
    ) {
        return previous == null
                ? java.util.Optional.empty()
                : items(previous, category).stream().filter(item -> item.id().equals(id)).findFirst();
    }

    private static List<SummaryItem> items(StructuredConversationSummary summary, Category category) {
        if (summary == null) {
            return List.of();
        }
        return switch (category) {
            case GOAL -> summary.goal() == null ? List.of() : List.of(summary.goal());
            case CONSTRAINT -> summary.constraints();
            case FACT -> summary.facts();
            case DECISION -> summary.decisions();
            case OPEN_QUESTION -> summary.openQuestions();
            case TODO -> summary.todos();
            case RESOURCE -> summary.resources();
        };
    }

    private static Set<String> previousDocumentRefs(StructuredConversationSummary previous) {
        if (previous == null) {
            return Set.of();
        }
        Set<String> refs = new HashSet<>();
        previous.allItems().stream()
                .flatMap(item -> item.sourceRefs().stream())
                .filter(ref -> ref.type() == SummarySourceType.DOCUMENT)
                .map(StructuredSummaryValidator::sourceKey)
                .forEach(refs::add);
        return refs;
    }

    private static String sourceKey(SummarySourceRef ref) {
        return ref.type() + "|" + ref.id() + "|" + String.valueOf(ref.versionId())
                + "|" + String.valueOf(ref.contentHash()) + "|" + ref.locator();
    }

    private static void requireText(String value, int maxLength, String label) {
        require(value != null && !value.isBlank() && value.length() <= maxLength, label + " is invalid");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new SummaryValidationException(message);
        }
    }

    private enum Category {
        GOAL,
        CONSTRAINT,
        FACT,
        DECISION,
        OPEN_QUESTION,
        TODO,
        RESOURCE
    }
}
