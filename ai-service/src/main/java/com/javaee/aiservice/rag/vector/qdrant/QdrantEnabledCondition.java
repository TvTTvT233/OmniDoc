package com.javaee.aiservice.rag.vector.qdrant;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

/** Enables Qdrant infrastructure only for shadow and qdrant modes. */
final class QdrantEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("ai.vector.mode", "baseline");
        String normalized = mode == null ? "baseline" : mode.trim().toLowerCase(Locale.ROOT);
        return "shadow".equals(normalized) || "qdrant".equals(normalized);
    }
}
