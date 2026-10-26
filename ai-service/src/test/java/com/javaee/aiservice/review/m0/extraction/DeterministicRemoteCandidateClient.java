package com.javaee.aiservice.review.m0.extraction;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

final class DeterministicRemoteCandidateClient implements RemoteCandidateClient {
    private final AtomicInteger calls;
    private final Function<byte[], RemoteExtractionResult> behavior;

    DeterministicRemoteCandidateClient(
            AtomicInteger calls,
            Function<byte[], RemoteExtractionResult> behavior
    ) {
        this.calls = Objects.requireNonNull(calls, "calls");
        this.behavior = Objects.requireNonNull(behavior, "behavior");
    }

    @Override
    public RemoteExtractionResult extract(byte[] confirmedPayloadBytes) {
        calls.incrementAndGet();
        return behavior.apply(confirmedPayloadBytes.clone());
    }
}
