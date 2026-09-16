package com.javaee.aiservice.agent.execution.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.mcp.contract.McpErrorCode;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolCallResult;
import com.javaee.aiservice.agent.execution.model.AgentToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the provider-neutral MCP response into the Agent's existing tool result model.
 */
public class McpToolResultAdapter {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+");
    private static final int MAX_SNIPPET_CHARS = 2_000;

    private final ObjectMapper objectMapper;

    public McpToolResultAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentToolResult adapt(McpToolCallResult callResult,
                                 Map<String, Object> arguments,
                                 int maxResultChars) {
        String exposedName = callResult == null ? "mcp.unknown" : callResult.exposedToolName();
        String query = extractQuery(arguments, callResult == null ? Map.of() : callResult.structuredContent());
        if (callResult == null) {
            return errorResult(exposedName, query, McpErrorCode.MCP_PROTOCOL_ERROR,
                    "MCP gateway returned no result", null);
        }

        if (callResult.error()) {
            return errorResult(exposedName, query, callResult.errorCode(),
                    sanitizeErrorMessage(callResult.errorMessage()), callResult);
        }

        int effectiveLimit = Math.max(1, maxResultChars);
        String rawPayload = safeJson(Map.of(
                "structuredContent", callResult.structuredContent(),
                "content", callResult.content()));
        if (rawPayload.length() > effectiveLimit) {
            return errorResult(exposedName, query, McpErrorCode.MCP_RESULT_TOO_LARGE,
                    "MCP result exceeded the local context limit of " + effectiveLimit + " characters",
                    callResult);
        }

        Map<String, Object> payload = preferredPayload(callResult);
        query = extractQuery(arguments, payload);
        List<Map<String, Object>> results = normalizeResults(payload, callResult.content());
        List<String> sources = collectSources(results);

        Map<String, Object> data = baseData(query, callResult, "success", McpErrorCode.NONE);
        data.put("results", results);
        data.put("sources", sources);
        String message = results.isEmpty()
                ? "MCP search completed, but no results were found"
                : "MCP search completed with " + results.size() + " result(s)";
        return AgentToolResult.success(exposedName, message, data);
    }

    private AgentToolResult errorResult(String exposedName,
                                        String query,
                                        McpErrorCode errorCode,
                                        String message,
                                        McpToolCallResult callResult) {
        McpErrorCode normalizedCode = errorCode == null || errorCode == McpErrorCode.NONE
                ? McpErrorCode.MCP_TOOL_ERROR
                : errorCode;
        String normalizedMessage = message == null || message.isBlank()
                ? "MCP tool call failed: " + normalizedCode
                : message;
        AgentToolResult result = AgentToolResult.error(exposedName, normalizedMessage);
        Map<String, Object> data = baseData(query, callResult, "error", normalizedCode);
        data.put("results", List.of());
        data.put("sources", List.of());
        data.put("errorCode", normalizedCode.name());
        result.setData(data);
        return result;
    }

    private Map<String, Object> baseData(String query,
                                         McpToolCallResult callResult,
                                         String status,
                                         McpErrorCode errorCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query == null ? "" : query);
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("serverId", callResult == null ? "" : nullToEmpty(callResult.serverId()));
        mcp.put("remoteToolName", callResult == null ? "" : nullToEmpty(callResult.remoteToolName()));
        mcp.put("exposedToolName", callResult == null ? "" : nullToEmpty(callResult.exposedToolName()));
        mcp.put("status", status);
        mcp.put("durationMs", callResult == null ? 0L : callResult.durationMs());
        if (errorCode != null && errorCode != McpErrorCode.NONE) {
            mcp.put("errorCode", errorCode.name());
        }
        data.put("mcp", mcp);
        return data;
    }

    private Map<String, Object> preferredPayload(McpToolCallResult callResult) {
        if (!callResult.structuredContent().isEmpty()) {
            return new LinkedHashMap<>(callResult.structuredContent());
        }
        for (Map<String, Object> block : callResult.content()) {
            Object text = block.get("text");
            if (text == null || text.toString().isBlank()) {
                continue;
            }
            try {
                Object parsed = objectMapper.readValue(text.toString(), Object.class);
                if (parsed instanceof Map<?, ?> map) {
                    return toStringObjectMap(map);
                }
                if (parsed instanceof List<?> list) {
                    return new LinkedHashMap<>(Map.of("results", list));
                }
            } catch (Exception ignored) {
                // Plain MCP text is normalized below as a compatibility fallback.
            }
        }
        return Map.of();
    }

    private List<Map<String, Object>> normalizeResults(Map<String, Object> payload,
                                                        List<Map<String, Object>> contentBlocks) {
        List<?> candidates = findResultCandidates(payload);
        if (candidates != null) {
            return normalizeCandidateList(candidates);
        }

        List<Map<String, Object>> blockCandidates = new ArrayList<>();
        StringBuilder plainText = new StringBuilder();
        for (Map<String, Object> block : contentBlocks) {
            if (looksLikeResult(block)) {
                blockCandidates.add(block);
            }
            Object text = block.get("text");
            if (text != null && !text.toString().isBlank()) {
                if (!plainText.isEmpty()) {
                    plainText.append('\n');
                }
                plainText.append(text);
            }
        }
        if (!blockCandidates.isEmpty()) {
            return normalizeCandidateList(blockCandidates);
        }
        return normalizePlainText(plainText.toString());
    }

    private List<?> findResultCandidates(Map<String, Object> payload) {
        Object results = payload.get("results");
        if (results instanceof List<?> list) {
            return list;
        }
        Object result = payload.get("result");
        if (result instanceof List<?> list) {
            return list;
        }
        if (result instanceof Map<?, ?> map) {
            return List.of(map);
        }
        for (String container : List.of("data", "response")) {
            Object nested = payload.get(container);
            if (nested instanceof Map<?, ?> map) {
                List<?> nestedResults = findResultCandidates(toStringObjectMap(map));
                if (nestedResults != null) {
                    return nestedResults;
                }
            }
        }
        return looksLikeResult(payload) ? List.of(payload) : null;
    }

    private List<Map<String, Object>> normalizeCandidateList(List<?> candidates) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object candidate : candidates) {
            Map<String, Object> map;
            if (candidate instanceof Map<?, ?> raw) {
                map = toStringObjectMap(raw);
            } else if (candidate != null) {
                map = Map.of("snippet", candidate.toString());
            } else {
                continue;
            }
            Map<String, Object> item = normalizeResult(map);
            if (!item.isEmpty()) {
                normalized.add(item);
            }
        }
        return List.copyOf(normalized);
    }

    private Map<String, Object> normalizeResult(Map<String, Object> candidate) {
        String title = firstText(candidate, "title", "name");
        String url = normalizeHttpUrl(firstText(candidate, "url", "link", "source"));
        String snippet = truncate(firstText(candidate, "snippet", "content", "description", "text"),
                MAX_SNIPPET_CHARS);
        Object score = candidate.get("score");
        if (isBlank(title) && isBlank(url) && isBlank(snippet)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("title", isBlank(title) ? (isBlank(url) ? "Search result" : url) : title);
        normalized.put("url", nullToEmpty(url));
        normalized.put("snippet", nullToEmpty(snippet));
        if (score instanceof Number number) {
            normalized.put("score", number);
        } else if (score != null) {
            try {
                normalized.put("score", Double.parseDouble(score.toString()));
            } catch (NumberFormatException ignored) {
                // Scores are optional and invalid provider values are omitted.
            }
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizePlainText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        Set<String> urls = new LinkedHashSet<>();
        while (matcher.find()) {
            String url = normalizeHttpUrl(matcher.group());
            if (!isBlank(url)) {
                urls.add(url);
            }
        }
        String snippet = truncate(text, MAX_SNIPPET_CHARS);
        if (urls.isEmpty()) {
            return List.of(Map.of(
                    "title", "MCP text result",
                    "url", "",
                    "snippet", snippet));
        }
        for (String url : urls) {
            results.add(Map.of(
                    "title", url,
                    "url", url,
                    "snippet", snippet));
        }
        return List.copyOf(results);
    }

    private List<String> collectSources(List<Map<String, Object>> results) {
        Set<String> sources = new LinkedHashSet<>();
        for (Map<String, Object> result : results) {
            String url = normalizeHttpUrl(stringValue(result.get("url")));
            if (!isBlank(url)) {
                sources.add(url);
            }
        }
        return List.copyOf(sources);
    }

    private String extractQuery(Map<String, Object> arguments, Map<String, Object> payload) {
        String payloadQuery = firstText(payload, "query", "searchQuery");
        if (!isBlank(payloadQuery)) {
            return payloadQuery;
        }
        if (arguments == null) {
            return "";
        }
        for (String key : List.of("query", "question", "q")) {
            String value = stringValue(arguments.get(key));
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean looksLikeResult(Map<String, Object> candidate) {
        return candidate.containsKey("url") || candidate.containsKey("link")
                || candidate.containsKey("snippet") || candidate.containsKey("content")
                || candidate.containsKey("title");
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "";
        }
        return message
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[-_]?key|token|authorization)(\\s*[:=]\\s*)[^\\s&,]+", "$1$2[REDACTED]")
                .replaceAll("(?i)([?&](?:api[-_]?key|token)=)[^&\\s]+", "$1[REDACTED]");
    }

    private String firstText(Map<String, Object> map, String... keys) {
        if (map == null) {
            return "";
        }
        for (String key : keys) {
            String value = stringValue(map.get(key));
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "…";
    }

    private String trimUrl(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("[.,;:!?，。；：！？、）)\\]}>]+$", "");
    }

    private String normalizeHttpUrl(String value) {
        String normalized = trimUrl(value);
        return normalized.startsWith("https://") || normalized.startsWith("http://") ? normalized : "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
