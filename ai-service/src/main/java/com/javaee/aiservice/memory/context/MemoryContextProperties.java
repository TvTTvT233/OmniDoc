package com.javaee.aiservice.memory.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "ai.memory.context")
public class MemoryContextProperties {

    private int defaultWindowTokens = 32768;
    private int reservedOutputTokens = 4096;
    private int safetyReservePercent = 10;
    private int historyPageSize = 200;
    private int blockOverheadTokens = 8;
    private Map<String, Integer> modelWindowTokens = new HashMap<>();

    public int inputBudget(String model) {
        int window = defaultWindowTokens;
        if (model != null && !model.isBlank()) {
            Integer configured = modelWindowTokens.get(model.toLowerCase(Locale.ROOT));
            if (configured != null) {
                window = configured;
            }
        }
        int safety = (int) Math.ceil(window * (safetyReservePercent / 100.0d));
        return Math.max(256, window - reservedOutputTokens - safety);
    }

    public int getDefaultWindowTokens() {
        return defaultWindowTokens;
    }

    public void setDefaultWindowTokens(int value) {
        this.defaultWindowTokens = Math.max(1024, value);
    }

    public int getReservedOutputTokens() {
        return reservedOutputTokens;
    }

    public void setReservedOutputTokens(int value) {
        this.reservedOutputTokens = Math.max(0, value);
    }

    public int getSafetyReservePercent() {
        return safetyReservePercent;
    }

    public void setSafetyReservePercent(int value) {
        this.safetyReservePercent = Math.max(0, Math.min(value, 50));
    }

    public int getHistoryPageSize() {
        return historyPageSize;
    }

    public void setHistoryPageSize(int value) {
        this.historyPageSize = Math.max(1, Math.min(value, 200));
    }

    public int getBlockOverheadTokens() {
        return blockOverheadTokens;
    }

    public void setBlockOverheadTokens(int value) {
        this.blockOverheadTokens = Math.max(1, value);
    }

    public Map<String, Integer> getModelWindowTokens() {
        return modelWindowTokens;
    }

    public void setModelWindowTokens(Map<String, Integer> values) {
        this.modelWindowTokens = new HashMap<>();
        if (values == null) {
            return;
        }
        values.forEach((key, value) -> {
            if (key != null && value != null && value >= 1024) {
                modelWindowTokens.put(key.toLowerCase(Locale.ROOT), value);
            }
        });
    }
}
