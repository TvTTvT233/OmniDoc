package com.javaee.aiservice.conversation.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> values;
    private SetOperations<String, Object> sets;
    private RuntimeStateProperties properties;
    private RuntimeStateCodec codec;
    private RuntimeStateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        doReturn(values).when(redisTemplate).opsForValue();
        doReturn(sets).when(redisTemplate).opsForSet();
        properties = new RuntimeStateProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        codec = new RuntimeStateCodec(objectMapper);
        service = new RuntimeStateService(
                redisTemplate,
                properties,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void whitelistAndHardLimitsAreFrozenAndTyped() {
        assertThat(Arrays.stream(RuntimeStateField.values()).map(RuntimeStateField::wireName))
                .containsExactly(
                        "currentDocumentId",
                        "selectedText",
                        "pageContext",
                        "pendingApproval",
                        "currentToolState"
                );
        assertThat(Arrays.stream(RuntimeStateUpdate.class.getRecordComponents())
                .noneMatch(component -> Map.class.isAssignableFrom(component.getType()))).isTrue();
        assertThat(properties.effectivePolicy(RuntimeStateField.SELECTED_TEXT).ttl())
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.effectivePolicy(RuntimeStateField.CURRENT_DOCUMENT_ID).ttl())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.effectivePolicy(RuntimeStateField.PENDING_APPROVAL).ttl())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.effectivePolicy(RuntimeStateField.CURRENT_TOOL_STATE).ttl())
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    void updateWritesEachFieldUnderExactScopeAndOwnTtl() {
        RuntimeStateScope scope = new RuntimeStateScope("conv-a", "trace-a");
        RuntimeStateUpdate.PendingApprovalReference approval =
                new RuntimeStateUpdate.PendingApprovalReference(
                        "approval-ref-1",
                        "file.delete",
                        NOW.plus(Duration.ofMinutes(3)).toEpochMilli()
                );
        RuntimeStateUpdate.CurrentToolState toolState = new RuntimeStateUpdate.CurrentToolState(
                "tool-call-1",
                "file.delete",
                RuntimeStateUpdate.ToolPhase.WAITING,
                "Waiting for user approval"
        );

        service.update(scope, new RuntimeStateUpdate(
                "doc-1",
                "selected",
                "/editor/doc-1",
                approval,
                toolState
        ));

        verify(sets).add(RuntimeStateKeys.traceIndexKey("conv-a"), "trace-a");
        verify(redisTemplate).expire(RuntimeStateKeys.traceIndexKey("conv-a"), Duration.ofHours(1));
        verify(values).set(
                RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_DOCUMENT_ID),
                codec.encode(RuntimeStateField.CURRENT_DOCUMENT_ID, "doc-1"),
                Duration.ofMinutes(30)
        );
        verify(values).set(
                RuntimeStateKeys.fieldKey(scope, RuntimeStateField.SELECTED_TEXT),
                codec.encode(RuntimeStateField.SELECTED_TEXT, "selected"),
                Duration.ofMinutes(10)
        );
        verify(values).set(
                RuntimeStateKeys.fieldKey(scope, RuntimeStateField.PAGE_CONTEXT),
                codec.encode(RuntimeStateField.PAGE_CONTEXT, "/editor/doc-1"),
                Duration.ofMinutes(10)
        );
        verify(values).set(
                RuntimeStateKeys.fieldKey(scope, RuntimeStateField.PENDING_APPROVAL),
                codec.encode(RuntimeStateField.PENDING_APPROVAL, approval),
                Duration.ofMinutes(3)
        );
        verify(values).set(
                RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_TOOL_STATE),
                codec.encode(RuntimeStateField.CURRENT_TOOL_STATE, toolState),
                Duration.ofHours(1)
        );
    }

    @Test
    void configuredValuesCanOnlyTightenFrozenLimits() {
        properties.getSelectedText().setTtl(Duration.ofHours(12));
        properties.getSelectedText().setMaxBytes(1024 * 1024);

        assertThat(properties.effectivePolicy(RuntimeStateField.SELECTED_TEXT).ttl())
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.effectivePolicy(RuntimeStateField.SELECTED_TEXT).maxBytes())
                .isEqualTo(16 * 1024);

        properties.getSelectedText().setTtl(Duration.ofMinutes(2));
        properties.getSelectedText().setMaxBytes(128);
        assertThat(properties.effectivePolicy(RuntimeStateField.SELECTED_TEXT).ttl())
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.effectivePolicy(RuntimeStateField.SELECTED_TEXT).maxBytes()).isEqualTo(128);

        properties.setLegacyCompatibilityTtl(Duration.ofMinutes(30));
        assertThat(properties.getLegacyCompatibilityTtl()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void oversizedAndSensitiveValuesAreRejectedBeforeRedisWrite() {
        String oversized = "x".repeat(16 * 1024 + 1);

        assertThatThrownBy(() -> service.update(
                new RuntimeStateScope("conv-a", "trace-a"),
                RuntimeStateUpdate.builder().selectedText(oversized).build()
        )).isInstanceOf(RuntimeStateException.Invalid.class)
                .extracting(error -> ((RuntimeStateException) error).getCode())
                .isEqualTo(RuntimeStateException.Invalid.CODE);

        assertThatThrownBy(() -> service.update(
                new RuntimeStateScope("conv-a", "trace-a"),
                RuntimeStateUpdate.builder().pageContext("Authorization: Bearer abc").build()
        )).isInstanceOf(RuntimeStateException.Invalid.class);

        assertThatThrownBy(() -> service.update(
                new RuntimeStateScope("conv-a", "trace-a"),
                RuntimeStateUpdate.builder().currentToolState(new RuntimeStateUpdate.CurrentToolState(
                        "call-1",
                        "tool-1",
                        RuntimeStateUpdate.ToolPhase.RUNNING,
                        "result contains access_token=secret"
                )).build()
        )).isInstanceOf(RuntimeStateException.Invalid.class);

        verify(values, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void conversationAndTraceProduceDistinctStorageKeysAndReads() {
        RuntimeStateScope first = new RuntimeStateScope("conv-a", "trace-a");
        RuntimeStateScope secondTrace = new RuntimeStateScope("conv-a", "trace-b");
        RuntimeStateScope secondConversation = new RuntimeStateScope("conv-b", "trace-a");
        when(values.get(RuntimeStateKeys.fieldKey(first, RuntimeStateField.SELECTED_TEXT)))
                .thenReturn(codec.encode(RuntimeStateField.SELECTED_TEXT, "only-a"));

        assertThat(service.readRequired(first).selectedText()).isEqualTo("only-a");
        assertThat(service.readRequired(secondTrace).selectedText()).isNull();
        assertThat(service.readRequired(secondConversation).selectedText()).isNull();
        assertThat(RuntimeStateKeys.fieldKey(first, RuntimeStateField.SELECTED_TEXT))
                .isNotEqualTo(RuntimeStateKeys.fieldKey(secondTrace, RuntimeStateField.SELECTED_TEXT))
                .isNotEqualTo(RuntimeStateKeys.fieldKey(secondConversation, RuntimeStateField.SELECTED_TEXT));
    }

    @Test
    void typedApprovalAndToolStateRoundTripWithoutMapFallback() {
        RuntimeStateScope scope = new RuntimeStateScope("conv-a", "trace-a");
        RuntimeStateUpdate.PendingApprovalReference approval =
                new RuntimeStateUpdate.PendingApprovalReference(
                        "approval-ref-1",
                        "file.delete",
                        NOW.plus(Duration.ofMinutes(5)).toEpochMilli()
                );
        RuntimeStateUpdate.CurrentToolState toolState = new RuntimeStateUpdate.CurrentToolState(
                "call-1",
                "file.delete",
                RuntimeStateUpdate.ToolPhase.WAITING,
                "Waiting for approval"
        );
        when(values.get(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.PENDING_APPROVAL)))
                .thenReturn(codec.encode(RuntimeStateField.PENDING_APPROVAL, approval));
        when(values.get(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_TOOL_STATE)))
                .thenReturn(codec.encode(RuntimeStateField.CURRENT_TOOL_STATE, toolState));

        RuntimeStateSnapshot snapshot = service.readRequired(scope);

        assertThat(snapshot.pendingApproval()).isEqualTo(approval);
        assertThat(snapshot.currentToolState()).isEqualTo(toolState);
    }

    @Test
    void currentDocumentReadSlidesItsTtlWithoutExtendingTurnInput() {
        RuntimeStateScope scope = new RuntimeStateScope("conv-a", "trace-a");
        when(values.get(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_DOCUMENT_ID)))
                .thenReturn(codec.encode(RuntimeStateField.CURRENT_DOCUMENT_ID, "doc-1"));

        RuntimeStateSnapshot snapshot = service.readRequired(scope);

        assertThat(snapshot.currentDocumentId()).isEqualTo("doc-1");
        verify(redisTemplate).expire(
                RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_DOCUMENT_ID),
                Duration.ofMinutes(30)
        );
        verify(redisTemplate, never()).expire(
                eq(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.SELECTED_TEXT)),
                any(Duration.class)
        );
    }

    @Test
    void turnInputIsConsumedWhileOtherStateRemains() {
        RuntimeStateScope scope = new RuntimeStateScope("conv-a", "trace-a");
        when(values.getAndDelete(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.SELECTED_TEXT)))
                .thenReturn(codec.encode(RuntimeStateField.SELECTED_TEXT, "selected"));
        when(values.getAndDelete(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.PAGE_CONTEXT)))
                .thenReturn(codec.encode(RuntimeStateField.PAGE_CONTEXT, "/page"));

        RuntimeStateService.TurnInput consumed = service.consumeTurnInput(scope);

        assertThat(consumed.selectedText()).isEqualTo("selected");
        assertThat(consumed.pageContext()).isEqualTo("/page");
        verify(values, never()).getAndDelete(
                RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_TOOL_STATE)
        );
    }

    @Test
    void traceAndConversationCleanupUseBoundedKnownKeys() {
        RuntimeStateScope scope = new RuntimeStateScope("conv-a", "trace-a");

        service.clearTrace(scope);

        verify(redisTemplate).delete(allFieldKeys("conv-a", "trace-a"));
        verify(sets).remove(RuntimeStateKeys.traceIndexKey("conv-a"), "trace-a");

        when(sets.members(RuntimeStateKeys.traceIndexKey("conv-a")))
                .thenReturn(Set.of("trace-a", "trace-b"));
        service.clearConversation("conv-a");

        List<String> expected = new java.util.ArrayList<>();
        expected.addAll(allFieldKeys("conv-a", "trace-a"));
        expected.addAll(allFieldKeys("conv-a", "trace-b"));
        expected.add(RuntimeStateKeys.traceIndexKey("conv-a"));
        expected.add(RuntimeStateKeys.legacyContextKey("conv-a"));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<String>> deletes =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate, times(2)).delete(deletes.capture());
        assertThat(deletes.getAllValues().get(1)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void redisUnavailableHasExplicitRequiredFailureAndOptionalDegrade() {
        RuntimeStateScope scope = new RuntimeStateScope("conv-a", "trace-a");
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));

        assertThat(service.readOptional(scope).availability())
                .isEqualTo(RuntimeStateReadResult.Availability.DEGRADED_UNAVAILABLE);
        assertThatThrownBy(() -> service.readRequired(scope))
                .isInstanceOf(RuntimeStateException.Unavailable.class)
                .extracting(error -> ((RuntimeStateException) error).getCode())
                .isEqualTo(RuntimeStateException.Unavailable.CODE);
    }

    @Test
    void redisUnavailableMakesRequiredWriteAndCleanupFail() {
        RuntimeStateScope scope = new RuntimeStateScope("conv-a", "trace-a");
        when(redisTemplate.opsForSet()).thenThrow(new RedisConnectionFailureException("offline"));

        assertThatThrownBy(() -> service.update(
                scope,
                RuntimeStateUpdate.builder().currentDocumentId("doc-1").build()
        )).isInstanceOf(RuntimeStateException.Unavailable.class)
                .extracting(error -> ((RuntimeStateException) error).getCode())
                .isEqualTo(RuntimeStateException.Unavailable.CODE);

        assertThatThrownBy(() -> service.clearConversation("conv-a"))
                .isInstanceOf(RuntimeStateException.Unavailable.class)
                .extracting(error -> ((RuntimeStateException) error).getCode())
                .isEqualTo(RuntimeStateException.Unavailable.CODE);
    }

    @Test
    void uncontrolledRedisMapIsRejectedInsteadOfDeserialized() {
        RuntimeStateScope scope = new RuntimeStateScope("conv-a", "trace-a");
        when(values.get(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_TOOL_STATE)))
                .thenReturn(Map.of(
                        "approvalToken", "secret",
                        "lastToolResults", List.of(Map.of("full", "result"))
                ));

        assertThatThrownBy(() -> service.readRequired(scope))
                .isInstanceOf(RuntimeStateException.Invalid.class)
                .extracting(error -> ((RuntimeStateException) error).getCode())
                .isEqualTo(RuntimeStateException.Invalid.CODE);
    }

    private static List<String> allFieldKeys(String conversationId, String traceId) {
        return Arrays.stream(RuntimeStateField.values())
                .map(field -> RuntimeStateKeys.fieldKey(conversationId, traceId, field))
                .toList();
    }
}
