package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import io.qdrant.client.grpc.JsonWithInt.ListValue;
import io.qdrant.client.grpc.JsonWithInt.NullValue;
import io.qdrant.client.grpc.JsonWithInt.Struct;
import io.qdrant.client.grpc.JsonWithInt.Value;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class QdrantPayloadMapper {

    static final String ID = "id";
    static final String USER_ID = "userId";
    static final String KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    static final String DOCUMENT_ID = "documentId";
    static final String EMBEDDING_MODEL = "embeddingModel";
    static final String EMBEDDING_DIMENSION = "embeddingDimension";
    static final String SCHEMA_VERSION = "schemaVersion";

    private static final Set<String> RESERVED_FIELDS = Set.of(
            ID,
            USER_ID,
            KNOWLEDGE_BASE_ID,
            DOCUMENT_ID,
            EMBEDDING_MODEL,
            EMBEDDING_DIMENSION,
            SCHEMA_VERSION);

    Map<String, Value> toPayload(VectorRecord record) {
        Map<String, Value> payload = new LinkedHashMap<>();
        record.metadata().forEach((key, value) -> payload.put(key, toValue(value)));
        payload.put(ID, stringValue(record.id()));
        payload.put(USER_ID, stringValue(record.scope().userId()));
        payload.put(KNOWLEDGE_BASE_ID, stringValue(record.scope().knowledgeBaseId()));
        payload.put(DOCUMENT_ID, stringValue(record.documentId()));
        payload.put(EMBEDDING_MODEL, stringValue(record.embedding().model()));
        payload.put(EMBEDDING_DIMENSION, integerValue(record.embedding().dimension()));
        payload.put(SCHEMA_VERSION, integerValue(record.embedding().schemaVersion()));
        return payload;
    }

    Map<String, Object> toMetadata(Map<String, Value> payload) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if (!RESERVED_FIELDS.contains(key)) {
                metadata.put(key, fromValue(value));
            }
        });
        return metadata;
    }

    String requiredString(Map<String, Value> payload, String field) {
        Value value = payload.get(field);
        if (value == null || value.getKindCase() != Value.KindCase.STRING_VALUE
                || value.getStringValue().isBlank()) {
            throw new VectorBackendException(
                    VectorBackendType.QDRANT,
                    VectorErrorCode.VECTOR_SEARCH_FAILED,
                    "Qdrant result payload is missing required field " + field,
                    false);
        }
        return value.getStringValue();
    }

    private Value toValue(Object value) {
        if (value == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (value instanceof String stringValue) {
            return stringValue(stringValue);
        }
        if (value instanceof Character character) {
            return stringValue(character.toString());
        }
        if (value instanceof Boolean booleanValue) {
            return Value.newBuilder().setBoolValue(booleanValue).build();
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return integerValue(((Number) value).longValue());
        }
        if (value instanceof BigInteger bigInteger) {
            try {
                return integerValue(bigInteger.longValueExact());
            } catch (ArithmeticException exception) {
                throw unsupported(value);
            }
        }
        if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw unsupported(value);
            }
            return Value.newBuilder().setDoubleValue(number).build();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Value> fields = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String stringKey) || stringKey.isBlank()) {
                    throw unsupported(value);
                }
                fields.put(stringKey, toValue(item));
            });
            return Value.newBuilder()
                    .setStructValue(Struct.newBuilder().putAllFields(fields).build())
                    .build();
        }
        if (value instanceof Iterable<?> iterable) {
            List<Value> values = new ArrayList<>();
            iterable.forEach(item -> values.add(toValue(item)));
            return listValue(values);
        }
        if (value.getClass().isArray()) {
            List<Value> values = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(toValue(Array.get(value, index)));
            }
            return listValue(values);
        }
        throw unsupported(value);
    }

    private Object fromValue(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE, KIND_NOT_SET -> null;
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> {
                Map<String, Object> result = new LinkedHashMap<>();
                value.getStructValue().getFieldsMap()
                        .forEach((key, item) -> result.put(key, fromValue(item)));
                yield result;
            }
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                    .map(this::fromValue)
                    .toList();
        };
    }

    private static Value stringValue(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }

    private static Value integerValue(long value) {
        return Value.newBuilder().setIntegerValue(value).build();
    }

    private static Value listValue(List<Value> values) {
        return Value.newBuilder()
                .setListValue(ListValue.newBuilder().addAllValues(values).build())
                .build();
    }

    private static VectorBackendException unsupported(Object value) {
        return new VectorBackendException(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                "Unsupported Qdrant payload value type: " + value.getClass().getSimpleName(),
                false);
    }
}
