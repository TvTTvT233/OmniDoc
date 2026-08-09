package com.javaee.aiservice.agent.execution.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata exposed to the planner so it can choose tools consistently.
 */
public class AgentToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, String> parameters;
    private final Map<String, AgentToolParameterDefinition> parameterSchema;
    private final boolean destructive;
    private final String category;
    private final boolean requiresUserAction;
    private final String riskLevel;
    private final boolean readOnly;
    private final Map<String, Object> inputSchema;
    private final Map<String, Object> sourceMetadata;

    public AgentToolDefinition(String name, String description, Map<String, String> parameters, boolean destructive) {
        this(name, description, parameters, destructive, "general", false);
    }

    public AgentToolDefinition(String name, String description, Map<String, String> parameters,
                               boolean destructive, String category, boolean requiresUserAction) {
        this(name, description, parameters, toLegacySchema(parameters), destructive, category,
                requiresUserAction, destructive ? "high" : "low");
    }

    public AgentToolDefinition(String name, String description, Map<String, String> parameters,
                               Map<String, AgentToolParameterDefinition> parameterSchema,
                               boolean destructive, String category, boolean requiresUserAction, String riskLevel) {
        this.name = name;
        this.description = description;
        this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
        this.parameterSchema = parameterSchema != null ? parameterSchema : new LinkedHashMap<>();
        this.destructive = destructive;
        this.category = category;
        this.requiresUserAction = requiresUserAction;
        this.riskLevel = riskLevel;
        this.readOnly = false;
        this.inputSchema = Map.of();
        this.sourceMetadata = Map.of();
    }

    public AgentToolDefinition(String name, String description, Map<String, String> parameters,
                               Map<String, AgentToolParameterDefinition> parameterSchema,
                               boolean destructive, String category, boolean requiresUserAction, String riskLevel,
                               boolean readOnly, Map<String, Object> inputSchema,
                               Map<String, Object> sourceMetadata) {
        this.name = name;
        this.description = description;
        this.parameters = immutableMap(parameters);
        this.parameterSchema = immutableMap(parameterSchema);
        this.destructive = destructive;
        this.category = category;
        this.requiresUserAction = requiresUserAction;
        this.riskLevel = riskLevel;
        this.readOnly = readOnly;
        this.inputSchema = immutableObjectMap(inputSchema);
        this.sourceMetadata = immutableObjectMap(sourceMetadata);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public Map<String, AgentToolParameterDefinition> getParameterSchema() {
        return parameterSchema;
    }

    public boolean isDestructive() {
        return destructive;
    }

    public String getCategory() {
        return category;
    }

    public boolean isRequiresUserAction() {
        return requiresUserAction;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Complete provider schema. Native tools keep this empty; MCP tools retain the discovered JSON Schema here.
     */
    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    /**
     * Non-secret origin metadata used to distinguish native and dynamically discovered tools.
     */
    public Map<String, Object> getSourceMetadata() {
        return sourceMetadata;
    }

    private static Map<String, AgentToolParameterDefinition> toLegacySchema(Map<String, String> parameters) {
        Map<String, AgentToolParameterDefinition> schema = new LinkedHashMap<>();
        if (parameters == null) {
            return schema;
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            schema.put(entry.getKey(), new AgentToolParameterDefinition(
                    entry.getKey(), "string", entry.getValue(), false, null));
        }
        return schema;
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("JSON Schema and metadata keys must be strings");
                }
                copy.put(stringKey, immutableValue(nestedValue));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            ArrayList<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
