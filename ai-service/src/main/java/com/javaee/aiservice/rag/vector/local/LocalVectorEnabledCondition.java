package com.javaee.aiservice.rag.vector.local;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

/** Keeps the Redis/HNSW baseline out of pure Qdrant mode. */
final class LocalVectorEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("ai.vector.mode", "baseline");
        String normalized = mode == null ? "baseline" : mode.trim().toLowerCase(Locale.ROOT);
        return "baseline".equals(normalized) || "shadow".equals(normalized);
    }
}
