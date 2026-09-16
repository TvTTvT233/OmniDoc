package com.javaee.aiservice.memory.summary;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class StructuredSummaryRenderer {

    public String render(StructuredConversationSummary summary) {
        StringBuilder value = new StringBuilder();
        append(value, "GOAL", summary.goal() == null ? List.of() : List.of(summary.goal()));
        append(value, "CONSTRAINTS", summary.constraints());
        append(value, "FACTS", summary.facts());
        append(value, "DECISIONS", summary.decisions());
        append(value, "OPEN_QUESTIONS", summary.openQuestions());
        append(value, "TODOS", summary.todos());
        append(value, "RESOURCES", summary.resources());
        String rendered = value.toString().trim();
        return rendered.isEmpty() ? "NO_STABLE_SUMMARY_ITEMS" : rendered;
    }

    private static void append(StringBuilder output, String label, List<SummaryItem> items) {
        if (items.isEmpty()) {
            return;
        }
        output.append(label).append(':').append('\n');
        for (SummaryItem item : items) {
            output.append("- [id=").append(safe(item.id()))
                    .append(" status=").append(safe(item.status()))
                    .append(" updatedSeq=").append(item.lastUpdatedSeq())
                    .append(" sources=");
            output.append(item.sourceRefs().stream()
                    .map(ref -> ref.type() + ":" + safe(ref.id()))
                    .toList());
            output.append("] ").append(item.content()).append('\n');
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\]\\[]", "_");
    }
}
