package com.javaee.aiservice.conversation.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Controlled, trace-scoped Runtime State storage.
 *
 * <p>Required operations fail with {@code RUNTIME_STATE_UNAVAILABLE}. Only
 * {@link #readOptional(RuntimeStateScope)} degrades, and it reports that degradation explicitly.
 * No method accepts an arbitrary state Map.</p>
 */
@Service
public class RuntimeStateService {

    private static final Pattern SAFE_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SENSITIVE_RUNTIME_TEXT = Pattern.compile(
            "(?i)(authorization|bearer\\s+|api[-_ ]?key|password|secret|approval[-_ ]?token|access[-_ ]?token|refresh[-_ ]?token)"
    );

    private final RedisTemplate<String, Object> redisTemplate;
    private final RuntimeStateProperties properties;
    private final RuntimeStateCodec codec;
    private final Clock clock;

    @Autowired
    public RuntimeStateService(
            RedisTemplate<String, Object> redisTemplate,
            RuntimeStateProperties properties
    ) {
        this(redisTemplate, properties, new ObjectMapper(), Clock.systemUTC());
    }

    RuntimeStateService(
            RedisTemplate<String, Object> redisTemplate,
            RuntimeStateProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.codec = new RuntimeStateCodec(objectMapper);
        this.clock = clock;
    }

    /**
     * Required read for approval, turn input or trace continuation.
     */
    public RuntimeStateSnapshot readRequired(RuntimeStateScope scope) {
        try {
            return readInternal(scope);
        } catch (RuntimeStateException.Invalid e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("read", e);
        }
    }

    /**
     * Optional read for ordinary requests which can continue without Runtime State.
     */
    public RuntimeStateReadResult readOptional(RuntimeStateScope scope) {
        try {
            return RuntimeStateReadResult.available(readInternal(scope));
        } catch (RuntimeStateException.Invalid e) {
            throw e;
        } catch (RuntimeException e) {
            return RuntimeStateReadResult.degraded(scope);
        }
    }

    /**
     * Persists only the frozen typed whitelist. A failed write never reports success.
     */
    public void update(RuntimeStateScope scope, RuntimeStateUpdate update) {
        if (update == null || update.isEmpty()) {
            return;
        }
        ValidatedUpdate validated = validate(update);
        try {
            registerTrace(scope);
            ValueOperations<String, Object> values = redisTemplate.opsForValue();
            for (ValidatedValue item : validated.values()) {
                values.set(
                        RuntimeStateKeys.fieldKey(scope, item.field()),
                        codec.encode(item.field(), item.value()),
                        item.ttl()
                );
            }
        } catch (RuntimeStateException.Invalid e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("update", e);
        }
    }

    /**
     * Reads and deletes one-turn input. Other trace state remains untouched.
     */
    public TurnInput consumeTurnInput(RuntimeStateScope scope) {
        try {
            ValueOperations<String, Object> values = redisTemplate.opsForValue();
            Object selected = values.getAndDelete(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.SELECTED_TEXT));
            Object page = values.getAndDelete(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.PAGE_CONTEXT));
            return new TurnInput(
                    cast(RuntimeStateField.SELECTED_TEXT, selected, String.class),
                    cast(RuntimeStateField.PAGE_CONTEXT, page, String.class)
            );
        } catch (RuntimeStateException.Invalid e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("consume-turn-input", e);
        }
    }

    public void clearFields(RuntimeStateScope scope, Set<RuntimeStateField> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        EnumSet<RuntimeStateField> controlled = EnumSet.copyOf(fields);
        try {
            redisTemplate.delete(controlled.stream()
                    .map(field -> RuntimeStateKeys.fieldKey(scope, field))
                    .toList());
        } catch (RuntimeException e) {
            throw unavailable("clear-fields", e);
        }
    }

    /**
     * Task-end cleanup for exactly one conversation + trace scope.
     */
    public void clearTrace(RuntimeStateScope scope) {
        try {
            redisTemplate.delete(allFieldKeys(scope.conversationId(), scope.traceId()));
            redisTemplate.opsForSet().remove(RuntimeStateKeys.traceIndexKey(scope.conversationId()), scope.traceId());
        } catch (RuntimeException e) {
            throw unavailable("clear-trace", e);
        }
    }

    /**
     * Cleanup interface for the later M02 Conversation deletion coordinator.
     * This method also removes the legacy ctx key, but does not claim the coordinator is wired.
     */
    public void clearConversation(String conversationId) {
        String controlledConversationId = RuntimeStateScope.requireConversationId(conversationId);
        String indexKey = RuntimeStateKeys.traceIndexKey(controlledConversationId);
        try {
            Set<Object> traceIds = redisTemplate.opsForSet().members(indexKey);
            List<String> keys = new ArrayList<>();
            if (traceIds != null) {
                for (Object traceId : traceIds) {
                    if (traceId != null) {
                        keys.addAll(allFieldKeys(controlledConversationId, traceId.toString()));
                    }
                }
            }
            keys.add(indexKey);
            keys.add(RuntimeStateKeys.legacyContextKey(controlledConversationId));
            redisTemplate.delete(keys);
        } catch (RuntimeException e) {
            throw unavailable("clear-conversation", e);
        }
    }

    private RuntimeStateSnapshot readInternal(RuntimeStateScope scope) {
        ValueOperations<String, Object> values = redisTemplate.opsForValue();
        String currentDocumentId = cast(
                RuntimeStateField.CURRENT_DOCUMENT_ID,
                values.get(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_DOCUMENT_ID)),
                String.class
        );
        if (currentDocumentId != null) {
            registerTrace(scope);
            redisTemplate.expire(
                    RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_DOCUMENT_ID),
                    properties.effectivePolicy(RuntimeStateField.CURRENT_DOCUMENT_ID).ttl()
            );
        }
        return new RuntimeStateSnapshot(
                scope,
                currentDocumentId,
                cast(RuntimeStateField.SELECTED_TEXT,
                        values.get(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.SELECTED_TEXT)), String.class),
                cast(RuntimeStateField.PAGE_CONTEXT,
                        values.get(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.PAGE_CONTEXT)), String.class),
                cast(RuntimeStateField.PENDING_APPROVAL,
                        values.get(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.PENDING_APPROVAL)),
                        RuntimeStateUpdate.PendingApprovalReference.class),
                cast(RuntimeStateField.CURRENT_TOOL_STATE,
                        values.get(RuntimeStateKeys.fieldKey(scope, RuntimeStateField.CURRENT_TOOL_STATE)),
                        RuntimeStateUpdate.CurrentToolState.class)
        );
    }

    private ValidatedUpdate validate(RuntimeStateUpdate update) {
        List<ValidatedValue> values = new ArrayList<>();
        addText(values, RuntimeStateField.CURRENT_DOCUMENT_ID, update.currentDocumentId(), true);
        addText(values, RuntimeStateField.SELECTED_TEXT, update.selectedText(), false);
        addText(values, RuntimeStateField.PAGE_CONTEXT, update.pageContext(), true);

        if (update.pendingApproval() != null) {
            RuntimeStateUpdate.PendingApprovalReference approval = update.pendingApproval();
            requireSafeReference("pendingApproval.referenceId", approval.referenceId());
            requireSafeReference("pendingApproval.toolName", approval.toolName());
            long remainingMillis = approval.expiresAtEpochMilli() - clock.millis();
            if (remainingMillis <= 0) {
                throw invalid("pendingApproval is already expired");
            }
            RuntimeStateProperties.EffectivePolicy policy =
                    properties.effectivePolicy(RuntimeStateField.PENDING_APPROVAL);
            validateSize(RuntimeStateField.PENDING_APPROVAL, structuredBytes(
                    approval.referenceId(), approval.toolName(), Long.toString(approval.expiresAtEpochMilli())
            ));
            values.add(new ValidatedValue(
                    RuntimeStateField.PENDING_APPROVAL,
                    approval,
                    Duration.ofMillis(Math.min(policy.ttl().toMillis(), remainingMillis))
            ));
        }

        if (update.currentToolState() != null) {
            RuntimeStateUpdate.CurrentToolState toolState = update.currentToolState();
            requireSafeReference("currentToolState.toolCallId", toolState.toolCallId());
            requireSafeReference("currentToolState.toolName", toolState.toolName());
            if (toolState.phase() == null) {
                throw invalid("currentToolState.phase must not be null");
            }
            requireText("currentToolState.statusSummary", toolState.statusSummary());
            rejectSensitiveText("currentToolState.statusSummary", toolState.statusSummary());
            validateSize(RuntimeStateField.CURRENT_TOOL_STATE, structuredBytes(
                    toolState.toolCallId(), toolState.toolName(), toolState.phase().name(), toolState.statusSummary()
            ));
            values.add(new ValidatedValue(
                    RuntimeStateField.CURRENT_TOOL_STATE,
                    toolState,
                    properties.effectivePolicy(RuntimeStateField.CURRENT_TOOL_STATE).ttl()
            ));
        }
        return new ValidatedUpdate(List.copyOf(values));
    }

    private void addText(
            Collection<ValidatedValue> values,
            RuntimeStateField field,
            String value,
            boolean rejectSensitive
    ) {
        if (value == null) {
            return;
        }
        requireText(field.wireName(), value);
        if (rejectSensitive) {
            rejectSensitiveText(field.wireName(), value);
        }
        validateSize(field, value.getBytes(StandardCharsets.UTF_8).length);
        values.add(new ValidatedValue(field, value, properties.effectivePolicy(field).ttl()));
    }

    private void validateSize(RuntimeStateField field, int actualBytes) {
        int maximum = properties.effectivePolicy(field).maxBytes();
        if (actualBytes > maximum) {
            throw invalid(field.wireName() + " exceeds " + maximum + " UTF-8 bytes");
        }
    }

    private static int structuredBytes(String... values) {
        int bytes = 0;
        for (String value : values) {
            if (value != null) {
                bytes += value.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return bytes;
    }

    private static void requireSafeReference(String name, String value) {
        requireText(name, value);
        if (!SAFE_REFERENCE.matcher(value).matches()) {
            throw invalid(name + " is not a bounded non-sensitive reference");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " must not be blank");
        }
    }

    private static void rejectSensitiveText(String name, String value) {
        if (SENSITIVE_RUNTIME_TEXT.matcher(value.toLowerCase(Locale.ROOT)).find()) {
            throw invalid(name + " contains credential or token material");
        }
    }

    private void registerTrace(RuntimeStateScope scope) {
        SetOperations<String, Object> sets = redisTemplate.opsForSet();
        String indexKey = RuntimeStateKeys.traceIndexKey(scope.conversationId());
        sets.add(indexKey, scope.traceId());
        redisTemplate.expire(indexKey, properties.traceIndexTtl());
    }

    private List<String> allFieldKeys(String conversationId, String traceId) {
        return java.util.Arrays.stream(RuntimeStateField.values())
                .map(field -> RuntimeStateKeys.fieldKey(conversationId, traceId, field))
                .toList();
    }

    private <T> T cast(RuntimeStateField field, Object stored, Class<T> expected) {
        if (stored == null) {
            return null;
        }
        Object decoded = codec.decode(field, stored);
        if (!expected.isInstance(decoded)) {
            throw invalid("Stored " + field.wireName() + " has an invalid value type");
        }
        return expected.cast(decoded);
    }

    private static RuntimeStateException.Invalid invalid(String message) {
        return new RuntimeStateException.Invalid(message);
    }

    private static RuntimeStateException.Unavailable unavailable(String operation, RuntimeException cause) {
        if (cause instanceof RuntimeStateException.Unavailable unavailable) {
            return unavailable;
        }
        return new RuntimeStateException.Unavailable(operation, cause);
    }

    private record ValidatedValue(RuntimeStateField field, Object value, Duration ttl) {
    }

    private record ValidatedUpdate(List<ValidatedValue> values) {
    }

    public record TurnInput(String selectedText, String pageContext) {
        public boolean isEmpty() {
            return selectedText == null && pageContext == null;
        }
    }
}
