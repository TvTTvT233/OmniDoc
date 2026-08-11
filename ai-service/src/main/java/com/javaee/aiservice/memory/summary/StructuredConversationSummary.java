package com.javaee.aiservice.memory.summary;

import java.util.List;

public record StructuredConversationSummary(
        String schemaVersion,
        String conversationId,
        long summaryVersion,
        SummaryCoverage coverage,
        SummaryItem goal,
        List<SummaryItem> constraints,
        List<SummaryItem> facts,
        List<SummaryItem> decisions,
        List<SummaryItem> openQuestions,
        List<SummaryItem> todos,
        List<SummaryItem> resources,
        SummaryMeta meta
) {
    public StructuredConversationSummary {
        constraints = immutable(constraints);
        facts = immutable(facts);
        decisions = immutable(decisions);
        openQuestions = immutable(openQuestions);
        todos = immutable(todos);
        resources = immutable(resources);
    }

    public List<SummaryItem> allItems() {
        java.util.ArrayList<SummaryItem> result = new java.util.ArrayList<>();
        if (goal != null) {
            result.add(goal);
        }
        result.addAll(constraints);
        result.addAll(facts);
        result.addAll(decisions);
        result.addAll(openQuestions);
        result.addAll(todos);
        result.addAll(resources);
        return List.copyOf(result);
    }

    private static List<SummaryItem> immutable(List<SummaryItem> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
