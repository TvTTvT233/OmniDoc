package com.javaee.aiservice.memory.summary;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "ai.memory.summary")
public class SummaryProperties {

    private boolean enabled = true;
    private int triggerInputTokens = 12_000;
    private int retainRecentTokens = 4_000;
    private int maxSourceTokens = 16_000;
    private int maxAttempts = 2;
    private Duration timeout = Duration.ofSeconds(20);
    private String model;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTriggerInputTokens() {
        return triggerInputTokens;
    }

    public void setTriggerInputTokens(int value) {
        triggerInputTokens = Math.max(256, value);
    }

    public int getRetainRecentTokens() {
        return retainRecentTokens;
    }

    public void setRetainRecentTokens(int value) {
        retainRecentTokens = Math.max(0, value);
    }

    public int getMaxSourceTokens() {
        return maxSourceTokens;
    }

    public void setMaxSourceTokens(int value) {
        maxSourceTokens = Math.max(256, value);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int value) {
        maxAttempts = Math.max(1, Math.min(3, value));
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration value) {
        timeout = value == null || value.isNegative() || value.isZero()
                ? Duration.ofSeconds(20)
                : value.compareTo(Duration.ofMinutes(2)) > 0 ? Duration.ofMinutes(2) : value;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
