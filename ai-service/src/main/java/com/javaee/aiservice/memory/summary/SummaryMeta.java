package com.javaee.aiservice.memory.summary;

import java.time.Instant;

public record SummaryMeta(long previousSummaryVersion, Instant generatedAt) {
}
