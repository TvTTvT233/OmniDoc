package com.javaee.aiservice.memory.summary;

import java.time.Duration;

public interface SummaryModelClient {

    String generate(String prompt, String model, Duration timeout);
}
