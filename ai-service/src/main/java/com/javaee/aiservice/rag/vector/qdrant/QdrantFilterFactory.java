package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.FieldCondition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.HasIdCondition;
import io.qdrant.client.grpc.Common.IsNullCondition;
import io.qdrant.client.grpc.Common.Match;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Common.Range;
import io.qdrant.client.grpc.Common.RepeatedIntegers;
import io.qdrant.client.grpc.Common.RepeatedStrings;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Builds Qdrant server-side filters with mandatory tenant clauses. */
final class QdrantFilterFactory {

    Filter forQuery(VectorFilter filter) {
        if (filter == null || filter.scope() == null) {
            throw filterRequired();
        }
        Filter.Builder builder = scope(filter.scope());
        if (filter.documentId() != null) {
            builder.addMust(keyword(QdrantPayloadMapper.DOCUMENT_ID, filter.documentId()));
        }
        filter.attributes().forEach((field, value) -> builder.addMust(attribute(field, value)));
        return builder.build();
    }

    Filter forScope(VectorScope scope) {
        return scope(scope).build();
    }

    Filter forDocument(VectorScope scope, String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        return scope(scope)
                .addMust(keyword(QdrantPayloadMapper.DOCUMENT_ID, documentId))
                .build();
    }

    Filter forId(VectorScope scope, PointId pointId) {
        if (pointId == null) {
            throw new IllegalArgumentException("pointId must not be null");
        }
        Condition idCondition = Condition.newBuilder()
                .setHasId(HasIdCondition.newBuilder().addHasId(pointId).build())
                .build();
        return scope(scope).addMust(idCondition).build();
    }

    private Filter.Builder scope(VectorScope scope) {
        if (scope == null) {
            throw filterRequired();
        }
        return Filter.newBuilder()
                .addMust(keyword(QdrantPayloadMapper.USER_ID, scope.userId()))
                .addMust(keyword(QdrantPayloadMapper.KNOWLEDGE_BASE_ID, scope.knowledgeBaseId()));
    }

    private Condition attribute(String field, Object value) {
        if (field == null || field.isBlank()) {
            throw configurationError("Qdrant filter attribute name must not be blank");
        }
        if (value == null) {
            return Condition.newBuilder()
                    .setIsNull(IsNullCondition.newBuilder().setKey(field).build())
                    .build();
        }
        if (value instanceof String stringValue) {
            return keyword(field, stringValue);
        }
        if (value instanceof Boolean booleanValue) {
            return fieldMatch(field, Match.newBuilder().setBoolean(booleanValue).build());
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return integer(field, ((Number) value).longValue());
        }
        if (value instanceof BigInteger bigInteger) {
            try {
                return integer(field, bigInteger.longValueExact());
            } catch (ArithmeticException exception) {
                throw configurationError("Qdrant integer filter value is out of range");
            }
        }
        if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw configurationError("Qdrant numeric filter value must be finite");
            }
            return Condition.newBuilder()
                    .setField(FieldCondition.newBuilder()
                            .setKey(field)
                            .setRange(Range.newBuilder().setGte(number).setLte(number).build())
                            .build())
                    .build();
        }
        if (value instanceof Iterable<?> iterable) {
            return collectionAttribute(field, iterable);
        }
        throw configurationError(
                "Unsupported Qdrant filter value type: " + value.getClass().getSimpleName());
    }

    private Condition collectionAttribute(String field, Iterable<?> iterable) {
        List<Object> values = new ArrayList<>();
        iterable.forEach(values::add);
        if (values.isEmpty()) {
            throw configurationError("Qdrant filter value list must not be empty");
        }
        if (values.stream().allMatch(String.class::isInstance)) {
            return fieldMatch(field, Match.newBuilder()
                    .setKeywords(RepeatedStrings.newBuilder()
                            .addAllStrings(values.stream().map(String.class::cast).toList())
                            .build())
                    .build());
        }
        if (values.stream().allMatch(QdrantFilterFactory::isIntegral)) {
            return fieldMatch(field, Match.newBuilder()
                    .setIntegers(RepeatedIntegers.newBuilder()
                            .addAllIntegers(values.stream()
                                    .map(Number.class::cast)
                                    .map(Number::longValue)
                                    .toList())
                            .build())
                    .build());
        }
        throw configurationError("Qdrant filter lists must contain only strings or integers");
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long;
    }

    private static Condition keyword(String field, String value) {
        return fieldMatch(field, Match.newBuilder().setKeyword(value).build());
    }

    private static Condition integer(String field, long value) {
        return fieldMatch(field, Match.newBuilder().setInteger(value).build());
    }

    private static Condition fieldMatch(String field, Match match) {
        return Condition.newBuilder()
                .setField(FieldCondition.newBuilder().setKey(field).setMatch(match).build())
                .build();
    }

    private static VectorBackendException filterRequired() {
        return new VectorBackendException(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_FILTER_REQUIRED,
                "Qdrant tenant filter is required",
                false);
    }

    private static VectorBackendException configurationError(String message) {
        return new VectorBackendException(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                message,
                false);
    }
}
