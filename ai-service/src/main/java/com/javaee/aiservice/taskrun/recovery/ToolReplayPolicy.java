package com.javaee.aiservice.taskrun.recovery;

import java.util.Set;

/** Server policy, never supplied by the planner. Unknown tools fail closed. */
public final class ToolReplayPolicy {
    private static final Set<String> SAFE = Set.of("direct-answer", "ask-user", "rag-answer", "rag-search",
            "text-summarize", "text-analyze", "keyword-extract", "text-correct", "file-download-url",
            "recycle-list", "file-version-list", "document-read", "document-write", "text-to-file");
    private ToolReplayPolicy() {}
    public static boolean replaySafe(String name) {
        // MCP catalog currently exposes validated read-only search tools only.
        return name != null && (SAFE.contains(name) || name.startsWith("mcp."));
    }
}
