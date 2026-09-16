package com.javaee.aiservice.agent.execution.tool;

import com.javaee.aiservice.agent.execution.mcp.contract.McpToolDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts provider-neutral MCP discovery metadata into the planner's tool definition model.
 */
public class McpToolDefinitionMapper {

    public static final String MCP_CATEGORY = "mcp-search";
    public static final String MCP_SOURCE = "mcp";
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "object", "string", "integer", "number", "boolean", "array"
    );

    public AgentToolDefinition map(McpToolDescriptor descriptor, String namespace,
                                   Collection<String> allowedRemoteNames) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        validateNamespace(descriptor, namespace);
        if (allowedRemoteNames == null || !allowedRemoteNames.contains(descriptor.remoteName())) {
            throw new IllegalArgumentException("MCP tool is not in the local allowlist: " + descriptor.remoteName());
        }

        Map<String, Object> inputSchema = descriptor.inputSchema();
        validateRootSchema(inputSchema);
        Set<String> required = requiredNames(inputSchema.get("required"));
        Map<String, Object> properties = schemaMap(inputSchema.get("properties"), "properties", true);
        if (!properties.keySet().containsAll(required)) {
            Set<String> missingDefinitions = new LinkedHashSet<>(required);
            missingDefinitions.removeAll(properties.keySet());
            throw new IllegalArgumentException("Required MCP parameters have no property schema: " + missingDefinitions);
        }

        Map<String, String> plannerParameters = new LinkedHashMap<>();
        Map<String, AgentToolParameterDefinition> parameterSchema = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String parameterName = entry.getKey();
            Map<String, Object> propertySchema = schemaMap(entry.getValue(),
                    "properties." + parameterName, false);
            String type = schemaType(propertySchema, parameterName);
            String description = textValue(propertySchema.get("description"));
            List<Object> allowedValues = enumValues(propertySchema.get("enum"), parameterName);
            Number minimum = numberValue(propertySchema.get("minimum"), "minimum", parameterName);
            Number maximum = numberValue(propertySchema.get("maximum"), "maximum", parameterName);
            String pattern = stringConstraint(propertySchema.get("pattern"), "pattern", parameterName);
            Object defaultValue = propertySchema.get("default");

            parameterSchema.put(parameterName, new AgentToolParameterDefinition(
                    parameterName,
                    type,
                    description,
                    required.contains(parameterName),
                    defaultValue,
                    allowedValues,
                    minimum,
                    maximum,
                    pattern
            ));
            plannerParameters.put(parameterName, plannerDescription(
                    description, type, propertySchema, required.contains(parameterName),
                    allowedValues, minimum, maximum, pattern));
        }

        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put("source", MCP_SOURCE);
        sourceMetadata.put("serverId", descriptor.serverId());
        sourceMetadata.put("remoteName", descriptor.remoteName());
        sourceMetadata.put("exposedName", descriptor.exposedName());
        sourceMetadata.put("namespace", namespace);
        sourceMetadata.put("annotations", descriptor.annotations());

        return new AgentToolDefinition(
                descriptor.exposedName(),
                descriptor.description(),
                plannerParameters,
                parameterSchema,
                false,
                MCP_CATEGORY,
                false,
                "low",
                true,
                inputSchema,
                sourceMetadata
        );
    }

    public String namespaceFor(String serverId) {
        requireText(serverId, "serverId");
        return "mcp." + serverId;
    }

    private void validateNamespace(McpToolDescriptor descriptor, String namespace) {
        requireText(namespace, "namespace");
        String expectedNamespace = namespaceFor(descriptor.serverId());
        if (!expectedNamespace.equals(namespace)) {
            throw new IllegalArgumentException("MCP namespace must be " + expectedNamespace);
        }
        String expectedName = namespace + "." + descriptor.remoteName();
        if (!expectedName.equals(descriptor.exposedName())) {
            throw new IllegalArgumentException("MCP exposed tool name must be " + expectedName);
        }
    }

    private void validateRootSchema(Map<String, Object> inputSchema) {
        Object type = inputSchema.get("type");
        if (type != null && !"object".equals(type)) {
            throw new IllegalArgumentException("MCP inputSchema root type must be object");
        }
        schemaMap(inputSchema.get("properties"), "properties", true);
        requiredNames(inputSchema.get("required"));
    }

    private Set<String> requiredNames(Object requiredValue) {
        if (requiredValue == null) {
            return Set.of();
        }
        if (!(requiredValue instanceof Collection<?> values)) {
            throw new IllegalArgumentException("MCP inputSchema.required must be an array");
        }
        Set<String> required = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("MCP inputSchema.required must contain non-blank strings");
            }
            required.add(name);
        }
        return required;
    }

    private Map<String, Object> schemaMap(Object value, String path, boolean nullable) {
        if (value == null && nullable) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("MCP inputSchema." + path + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, nestedValue) -> {
            if (!(key instanceof String stringKey) || stringKey.isBlank()) {
                throw new IllegalArgumentException("MCP inputSchema." + path + " keys must be non-blank strings");
            }
            result.put(stringKey, nestedValue);
        });
        return result;
    }

    private String schemaType(Map<String, Object> propertySchema, String parameterName) {
        Object type = propertySchema.get("type");
        if (!(type instanceof String stringType) || !SUPPORTED_TYPES.contains(stringType)) {
            throw new IllegalArgumentException("Unsupported or missing MCP schema type for " + parameterName + ": " + type);
        }
        return stringType;
    }

    private List<Object> enumValues(Object value, String parameterName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
            throw new IllegalArgumentException("MCP enum for " + parameterName + " must be a non-empty array");
        }
        return Collections.unmodifiableList(new ArrayList<>(collection));
    }

    private Number numberValue(Object value, String constraint, String parameterName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("MCP " + constraint + " for " + parameterName + " must be numeric");
        }
        return number;
    }

    private String stringConstraint(Object value, String constraint, String parameterName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("MCP " + constraint + " for " + parameterName + " must be a string");
        }
        return stringValue;
    }

    private String plannerDescription(String description, String type, Map<String, Object> propertySchema,
                                      boolean required, List<Object> allowedValues,
                                      Number minimum, Number maximum, String pattern) {
        List<String> details = new ArrayList<>();
        details.add(plannerType(type, propertySchema));
        details.add(required ? "required" : "optional");
        if (allowedValues != null) {
            details.add("enum=" + allowedValues);
        }
        if (minimum != null) {
            details.add("minimum=" + minimum);
        }
        if (maximum != null) {
            details.add("maximum=" + maximum);
        }
        if (pattern != null) {
            details.add("pattern=" + pattern);
        }
        String prefix = description.isBlank() ? "" : description + " ";
        return prefix + "(" + String.join(", ", details) + ")";
    }

    private String plannerType(String type, Map<String, Object> propertySchema) {
        if (!"array".equals(type)) {
            return "type=" + type;
        }
        Object itemsValue = propertySchema.get("items");
        if (!(itemsValue instanceof Map<?, ?> items)) {
            return "type=array";
        }
        Object itemType = items.get("type");
        return itemType instanceof String ? "type=array<" + itemType + ">" : "type=array";
    }

    private String textValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
