package com.javaee.aiservice.rag.vector.migration;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Migration is available during shadow validation, before the final Qdrant-only cutover. */
final class ShadowMigrationEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("ai.vector.mode", "baseline");
        return mode != null && "shadow".equalsIgnoreCase(mode.trim());
    }
}
