package com.javaee.aiservice.agent.execution.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Central catalog for every tool the Agent planner is allowed to use.
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentToolDefinition> tools = new LinkedHashMap<>();
    private final Map<String, String> mcpToolOwners = new LinkedHashMap<>();

    public AgentToolRegistry() {
        register("direct-answer", "不调用外部工具，直接基于模型和上下文回答用户。",
                Map.of("question", "用户问题"), Set.of(), false, "llm", false);
        register("ask-user", "当任务参数缺失或存在歧义时，向用户提出澄清问题。不要在已经掌握所需信息时使用。",
                Map.of("question", "需要向用户澄清的问题",
                        "missingFields", "本次缺失的字段名，逗号分隔，可选",
                        "options", "可供用户选择的候选项，分号分隔，可选"),
                Set.of("question"), false, "interaction", true);
        register("rag-answer", "检索知识库并调用模型生成最终问答答案。",
                Map.of("question", "用户问题", "topK", "检索条数，默认3",
                        "rerankStrategy", "重排序策略，默认HYBRID；只影响候选片段排序，不代表业务检索策略"),
                Set.of("question"), false, "rag", false);
        register("rag-search", "只检索知识库，返回相关片段和来源，不生成答案。",
                Map.of("query", "查询词", "topK", "检索条数，默认5",
                        "rerankStrategy", "重排序策略，默认HYBRID；只影响候选片段排序，不代表业务检索策略"),
                Set.of("query"), false, "rag", false);
        register("text-summarize", "对文本进行摘要。",
                Map.of("content", "待摘要文本", "maxLength", "摘要最大长度，默认300", "model", "可选模型代码"),
                Set.of("content"), false, "text", false);
        register("text-analyze", "统计文本字符、中文、英文、数字、空格、标点、行数。",
                Map.of("content", "待分析文本"), Set.of("content"), false, "text", false);
        register("keyword-extract", "从文本中提取关键词。",
                Map.of("content", "待提取文本", "count", "关键词数量，默认8", "model", "可选模型代码"),
                Set.of("content"), false, "text", false);
        register("text-correct", "对文本进行纠错、润色或改写。",
                Map.of("content", "待处理文本", "instruction", "纠错/润色/改写要求", "model", "可选模型代码"),
                Set.of("content"), false, "text", false);
        register("file-download-url", "生成文件预签名下载地址。",
                Map.of("bucketName", "存储桶名称，可选", "objectName", "对象名称，必填"),
                Set.of("objectName"), false, "file", false);
        register("file-delete", "永久删除指定文件，不进入回收站。可传前端documentId，由服务端删除对应业务文档；也兼容直接传objectName永久删除MinIO对象。",
                Map.of("bucketName", "存储桶名称，可选",
                        "objectName", "对象名称，与documentId二选一",
                        "documentId", "前端业务文档ID，与objectName二选一"),
                Set.of(), true, "file", false);
        register("file-restore", "从回收站恢复文件。",
                Map.of("recycleId", "回收站记录ID", "bucketName", "存储桶名称，可选", "newObjectName", "新对象名称，可选"),
                Set.of("recycleId"), true, "file", false);
        register("recycle-list", "查看回收站文件列表。",
                Map.of("bucketName", "存储桶名称，可选"), Set.of(), false, "file", false);
        register("file-version-list", "查看文件版本列表。",
                Map.of("bucketName", "存储桶名称，可选", "objectName", "对象名称，必填"),
                Set.of("objectName"), false, "file", false);
        register("file-version-switch", "切换文件当前版本。",
                Map.of("bucketName", "存储桶名称，可选", "objectName", "对象名称，必填",
                        "targetVersionId", "目标版本ID"), Set.of("objectName", "targetVersionId"), true, "file", false);
        register("html-ppt-generate", "根据大纲生成 HTML PPT。",
                Map.of("outline", "PPT大纲", "theme", "主题，默认tokyo-night", "title", "标题", "model", "可选模型代码"),
                Set.of("outline"), false, "generation", false);
        register("document-read", "读取业务文档当前内容。用于在扩写、改写、润色前获取原文档。",
                Map.of("documentId", "业务文档ID，必填"),
                Set.of("documentId"), false, "document", false);
        register("document-write", "将AI生成的文本内容返回给前端，由当前编辑器直接写入页面文档；不在服务端保存文件或刷新MinIO对象。",
                Map.of("documentId", "业务文档ID，可选，用于前端校验当前文档",
                        "content", "要写回文档的完整文本内容，必填",
                        "writeMode", "写入模式: append(追加,默认) / overwrite(覆盖) / replace-selection(替换选中内容) / insert(插入)",
                        "changeLog", "本次前端增量修改说明，默认由Agent生成",
                        "selectionText", "前端当前选中的文本，可选",
                        "insertAfterText", "从当前文档中复制的一小段锚点文本，前端会把内容插入到该段后面，可选",
                        "contentFormat", "内容格式: plain_text(默认) / html"),
                Set.of("content"), false, "document", false);
        register("text-to-file", "兼容旧的写文件工具名：将AI生成文本返回给前端编辑器待写入，不直接调用MinIO。",
                Map.of("content", "要写入的文本内容，必填",
                        "objectName", "旧流程的文件对象名称，可选，仅用于兼容展示",
                        "bucketName", "旧流程的存储桶名称，可选，仅用于兼容展示",
                        "contentType", "内容类型，默认text/plain",
                        "writeMode", "写入模式: append(追加,默认) / overwrite(覆盖) / replace-selection(替换选中内容) / insert(插入)",
                        "changeLog", "本次前端增量修改说明，默认由Agent生成",
                        "insertAfterText", "从当前文档中复制的一小段锚点文本，前端会把内容插入到该段后面，可选",
                        "selectionText", "前端当前选中的文本，可选"),
                Set.of("content"), false, "file", false);
    }

    public synchronized AgentToolDefinition get(String name) {
        return tools.get(name);
    }

    public synchronized List<AgentToolDefinition> list() {
        return new ArrayList<>(tools.values());
    }

    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * Atomically replaces all MCP tools owned by one configured server.
     *
     * <p>The complete candidate set is validated before the live catalog is mutated. A conflict or invalid
     * definition therefore leaves the previous server snapshot untouched.</p>
     */
    public synchronized void replaceMcpTools(String serverId, List<AgentToolDefinition> definitions) {
        requireText(serverId, "serverId");
        Objects.requireNonNull(definitions, "definitions must not be null");

        Map<String, AgentToolDefinition> nextTools = new LinkedHashMap<>(tools);
        Map<String, String> nextOwners = new LinkedHashMap<>(mcpToolOwners);
        List<String> previousNames = nextOwners.entrySet().stream()
                .filter(entry -> serverId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        previousNames.forEach(name -> {
            nextTools.remove(name);
            nextOwners.remove(name);
        });

        Set<String> candidateNames = new java.util.HashSet<>();
        for (AgentToolDefinition definition : definitions) {
            validateMcpDefinition(serverId, definition);
            String name = definition.getName();
            if (!candidateNames.add(name)) {
                throw new IllegalArgumentException("Duplicate MCP tool name in refresh: " + name);
            }
            if (nextTools.containsKey(name)) {
                String owner = nextOwners.get(name);
                String conflictSource = owner == null ? "native tool" : "MCP server " + owner;
                throw new IllegalStateException("MCP tool name conflicts with " + conflictSource + ": " + name);
            }
            nextTools.put(name, definition);
            nextOwners.put(name, serverId);
        }

        tools.clear();
        tools.putAll(nextTools);
        mcpToolOwners.clear();
        mcpToolOwners.putAll(nextOwners);
    }

    /**
     * Removes one server's dynamic tools without changing native tools or tools owned by other servers.
     *
     * @return number of removed tools
     */
    public synchronized int removeMcpTools(String serverId) {
        requireText(serverId, "serverId");
        List<String> names = mcpToolOwners.entrySet().stream()
                .filter(entry -> serverId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        names.forEach(name -> {
            tools.remove(name);
            mcpToolOwners.remove(name);
        });
        return names.size();
    }

    public synchronized List<AgentToolDefinition> listMcpTools(String serverId) {
        requireText(serverId, "serverId");
        return mcpToolOwners.entrySet().stream()
                .filter(entry -> serverId.equals(entry.getValue()))
                .map(entry -> tools.get(entry.getKey()))
                .toList();
    }

    private void register(String name, String description, Map<String, String> parameters, Set<String> requiredParameters,
                          boolean destructive, String category, boolean requiresUserAction) {
        Map<String, AgentToolParameterDefinition> parameterSchema = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String parameterName = entry.getKey();
            parameterSchema.put(parameterName, new AgentToolParameterDefinition(
                    parameterName,
                    inferType(parameterName),
                    entry.getValue(),
                    requiredParameters.contains(parameterName),
                    defaultValue(parameterName),
                    allowedValues(parameterName),
                    minValue(parameterName),
                    maxValue(parameterName),
                    pattern(parameterName)
            ));
        }
        tools.put(name, new AgentToolDefinition(name, description, parameters, parameterSchema,
                destructive, category, requiresUserAction, destructive ? "high" : "low"));
    }

    private List<Object> allowedValues(String parameterName) {
        return switch (parameterName) {
            case "rerankStrategy" -> List.of("HYBRID", "VECTOR", "BM25");
            case "writeMode" -> List.of("overwrite", "append", "insert", "replace-selection");
            default -> null;
        };
    }

    private Number minValue(String parameterName) {
        return switch (parameterName) {
            case "topK", "count" -> 1;
            case "maxLength" -> 50;
            default -> null;
        };
    }

    private Number maxValue(String parameterName) {
        return switch (parameterName) {
            case "topK" -> 50;
            case "count" -> 50;
            case "maxLength" -> 5000;
            default -> null;
        };
    }

    private String pattern(String parameterName) {
        return null;
    }

    private String inferType(String parameterName) {
        if (Set.of("topK", "count", "maxLength").contains(parameterName)) {
            return "integer";
        }
        if (Set.of("requireConfirmation", "reindexAfterWrite").contains(parameterName)) {
            return "boolean";
        }
        return "string";
    }

    private Object defaultValue(String parameterName) {
        return switch (parameterName) {
            case "topK" -> 5;
            case "count" -> 8;
            case "maxLength" -> 300;
            case "rerankStrategy" -> "HYBRID";
            case "requireConfirmation" -> true;
            case "writeMode" -> "append";
            case "contentType" -> "text/plain";
            case "reindexAfterWrite" -> true;
            default -> null;
        };
    }

    private void validateMcpDefinition(String serverId, AgentToolDefinition definition) {
        Objects.requireNonNull(definition, "MCP tool definition must not be null");
        requireText(definition.getName(), "tool name");
        Map<String, Object> metadata = definition.getSourceMetadata();
        if (!"mcp".equals(metadata.get("source")) || !serverId.equals(metadata.get("serverId"))) {
            throw new IllegalArgumentException("MCP tool source metadata does not match server " + serverId);
        }
        if (!"mcp-search".equals(definition.getCategory())
                || definition.isDestructive()
                || definition.isRequiresUserAction()
                || !definition.isReadOnly()
                || !"low".equals(definition.getRiskLevel())) {
            throw new IllegalArgumentException("MCP search tools must use the local read-only low-risk policy");
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
