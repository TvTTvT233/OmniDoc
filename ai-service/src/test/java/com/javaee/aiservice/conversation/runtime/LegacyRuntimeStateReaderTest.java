package com.javaee.aiservice.conversation.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyRuntimeStateReaderTest {

    private RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, Object, Object> hashes;
    private LegacyRuntimeStateReader reader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        hashes = mock(HashOperations.class);
        doReturn(hashes).when(redisTemplate).opsForHash();
        reader = new LegacyRuntimeStateReader(redisTemplate, new RuntimeStateProperties());
    }

    @Test
    void readsOnlyBoundedScalarWhitelistAndAppliesCompatibilityTtl() {
        Map<Object, Object> legacy = new LinkedHashMap<>();
        legacy.put("documentId", "legacy-doc");
        legacy.put("selectedText", "legacy-selection");
        legacy.put("pageContext", "/legacy/page");
        legacy.put("documentContent", "full-document");
        legacy.put("approvalToken", "secret-token");
        legacy.put("pendingApproval", Map.of("agentApprovalToken", "secret-token"));
        legacy.put("currentToolState", Map.of("lastToolResults", List.of("full-result")));
        legacy.put("lastToolResults", List.of(Map.of("secret", "full-result")));
        legacy.put("arbitrary", "value");
        when(hashes.entries("ctx:conv-a")).thenReturn(legacy);

        LegacyRuntimeStateReader.LegacyReadResult result = reader.readOptional("conv-a");

        assertThat(result.availability()).isEqualTo(LegacyRuntimeStateReader.Availability.AVAILABLE);
        assertThat(result.values()).containsOnly(
                Map.entry("currentDocumentId", "legacy-doc"),
                Map.entry("selectedText", "legacy-selection"),
                Map.entry("pageContext", "/legacy/page")
        );
        verify(redisTemplate).expire("ctx:conv-a", Duration.ofMinutes(10));
    }

    @Test
    void oversizedLegacyValueIsIgnored() {
        when(hashes.entries("ctx:conv-a")).thenReturn(Map.of(
                "selectedText", "x".repeat(16 * 1024 + 1)
        ));

        assertThat(reader.readOptional("conv-a").values()).isEmpty();
    }

    @Test
    void legacyRedisFailureIsReportedAsDegradedEmpty() {
        when(hashes.entries("ctx:conv-a"))
                .thenThrow(new RedisConnectionFailureException("offline"));

        LegacyRuntimeStateReader.LegacyReadResult result = reader.readOptional("conv-a");

        assertThat(result.availability())
                .isEqualTo(LegacyRuntimeStateReader.Availability.DEGRADED_UNAVAILABLE);
        assertThat(result.values()).isEmpty();
    }
}
