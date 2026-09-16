package com.javaee.aiservice.conversation.runtime;

/**
 * Optional reads surface Redis degradation instead of pretending an unavailable store was empty.
 */
public record RuntimeStateReadResult(
        Availability availability,
        RuntimeStateSnapshot snapshot
) {

    public static RuntimeStateReadResult available(RuntimeStateSnapshot snapshot) {
        return new RuntimeStateReadResult(Availability.AVAILABLE, snapshot);
    }

    public static RuntimeStateReadResult degraded(RuntimeStateScope scope) {
        return new RuntimeStateReadResult(Availability.DEGRADED_UNAVAILABLE, RuntimeStateSnapshot.empty(scope));
    }

    public enum Availability {
        AVAILABLE,
        DEGRADED_UNAVAILABLE
    }
}
