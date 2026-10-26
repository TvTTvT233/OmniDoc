package com.javaee.aiservice.review.m0.checklist;

import java.util.UUID;

@FunctionalInterface
public interface M0IdGenerator {
    String next(String prefix);

    static M0IdGenerator randomUuid() {
        return prefix -> prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
