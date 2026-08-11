package com.javaee.aiservice.memory.summary;

public enum SummaryUpdateStatus {
    DISABLED,
    BELOW_THRESHOLD,
    SOURCE_TOO_LARGE,
    UPDATED,
    IDEMPOTENT,
    CONFLICT,
    FAILED
}
