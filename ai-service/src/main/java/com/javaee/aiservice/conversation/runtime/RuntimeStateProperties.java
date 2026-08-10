package com.javaee.aiservice.conversation.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Runtime State policy. Configuration may tighten but never enlarge the frozen hard limits.
 */
@Component
@ConfigurationProperties(prefix = "ai.memory.runtime-state")
public class RuntimeStateProperties {

    private FieldPolicy currentDocumentId = new FieldPolicy(Duration.ofMinutes(30), 512);
    private FieldPolicy selectedText = new FieldPolicy(Duration.ofMinutes(10), 16 * 1024);
    private FieldPolicy pageContext = new FieldPolicy(Duration.ofMinutes(10), 4 * 1024);
    private FieldPolicy pendingApproval = new FieldPolicy(Duration.ofMinutes(5), 1024);
    private FieldPolicy currentToolState = new FieldPolicy(Duration.ofHours(1), 2 * 1024);
    private Duration legacyCompatibilityTtl = Duration.ofMinutes(10);

    public FieldPolicy getCurrentDocumentId() {
        return currentDocumentId;
    }

    public void setCurrentDocumentId(FieldPolicy currentDocumentId) {
        this.currentDocumentId = currentDocumentId;
    }

    public FieldPolicy getSelectedText() {
        return selectedText;
    }

    public void setSelectedText(FieldPolicy selectedText) {
        this.selectedText = selectedText;
    }

    public FieldPolicy getPageContext() {
        return pageContext;
    }

    public void setPageContext(FieldPolicy pageContext) {
        this.pageContext = pageContext;
    }

    public FieldPolicy getPendingApproval() {
        return pendingApproval;
    }

    public void setPendingApproval(FieldPolicy pendingApproval) {
        this.pendingApproval = pendingApproval;
    }

    public FieldPolicy getCurrentToolState() {
        return currentToolState;
    }

    public void setCurrentToolState(FieldPolicy currentToolState) {
        this.currentToolState = currentToolState;
    }

    public Duration getLegacyCompatibilityTtl() {
        return boundedPositiveDuration(
                legacyCompatibilityTtl,
                Duration.ofMinutes(10),
                Duration.ofMinutes(10)
        );
    }

    public void setLegacyCompatibilityTtl(Duration legacyCompatibilityTtl) {
        this.legacyCompatibilityTtl = legacyCompatibilityTtl;
    }

    public EffectivePolicy effectivePolicy(RuntimeStateField field) {
        FieldPolicy configured = switch (field) {
            case CURRENT_DOCUMENT_ID -> currentDocumentId;
            case SELECTED_TEXT -> selectedText;
            case PAGE_CONTEXT -> pageContext;
            case PENDING_APPROVAL -> pendingApproval;
            case CURRENT_TOOL_STATE -> currentToolState;
        };
        Duration ttl = boundedPositiveDuration(
                configured == null ? null : configured.getTtl(),
                field.hardMaximumTtl(),
                field.hardMaximumTtl()
        );
        int maxBytes = configured == null || configured.getMaxBytes() <= 0
                ? field.hardMaximumBytes()
                : Math.min(configured.getMaxBytes(), field.hardMaximumBytes());
        return new EffectivePolicy(ttl, maxBytes);
    }

    public Duration traceIndexTtl() {
        return RuntimeStateField.CURRENT_TOOL_STATE.hardMaximumTtl();
    }

    private static Duration boundedPositiveDuration(Duration value, Duration fallback, Duration hardMaximum) {
        Duration chosen = value == null || value.isZero() || value.isNegative() ? fallback : value;
        return chosen.compareTo(hardMaximum) > 0 ? hardMaximum : chosen;
    }

    public record EffectivePolicy(Duration ttl, int maxBytes) {
    }

    public static class FieldPolicy {
        private Duration ttl;
        private int maxBytes;

        public FieldPolicy() {
        }

        public FieldPolicy(Duration ttl, int maxBytes) {
            this.ttl = ttl;
            this.maxBytes = maxBytes;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }
}
