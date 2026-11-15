package com.javaee.documentservice.consistency;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;

class DocumentClockPrecisionTest extends DocumentConsistencyTransactionTest {
    @Test void newlyEnqueuedTaskIsImmediatelyClaimableAtSubMicrosecondClockBoundary() throws Exception {
        DocumentIndexService target=AopTestUtils.getUltimateTargetObject(index);
        Clock original=(Clock)ReflectionTestUtils.getField(target,"clock");
        try {
            ReflectionTestUtils.setField(target,"clock",Clock.fixed(Instant.parse("2026-11-12T00:00:00.123456789Z"),ZoneOffset.UTC));
            var d=create();save(d.getId(),1,"fixed clock evidence");
            assertThat(claim()).as("a fresh task must be due even when DB rounds nanoseconds to microseconds").isNotNull();
        } finally {ReflectionTestUtils.setField(target,"clock",original);}
    }
}
