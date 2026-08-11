package com.javaee.aiservice.conversation.runtime;

import com.javaee.aiservice.agent.execution.approval.AgentApprovalService;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Required cleanup coordinator for data that must not survive a deleted Conversation.
 *
 * <p>Every operation is idempotent. A storage failure is propagated so the caller retains the
 * durable Conversation and can retry instead of reporting a partially completed delete as
 * successful.</p>
 */
@Service
public class ConversationRuntimeDerivativeCleanup implements RuntimeStateCleanup {

    private final RuntimeStateService runtimeStateService;
    private final AgentTaskRegistry taskRegistry;
    private final AgentApprovalService approvalService;

    public ConversationRuntimeDerivativeCleanup(
            RuntimeStateService runtimeStateService,
            AgentTaskRegistry taskRegistry,
            AgentApprovalService approvalService
    ) {
        this.runtimeStateService = runtimeStateService;
        this.taskRegistry = taskRegistry;
        this.approvalService = approvalService;
    }

    @Override
    public void clearConversation(String conversationId) {
        runtimeStateService.clearConversation(conversationId);
        List<Map<String, Object>> snapshots = taskRegistry.findByConversationIdRequired(conversationId);
        for (Map<String, Object> snapshot : snapshots) {
            Map<String, Object> pendingApproval = pendingApproval(snapshot);
            Object token = pendingApproval.get("agentApprovalToken");
            if (token != null && !token.toString().isBlank()) {
                // A false result means the token already expired or was already removed, which is
                // an idempotent success. Redis failures still propagate from AgentApprovalService.
                approvalService.cancel(token.toString());
            }
            Object traceId = snapshot.get("traceId");
            if (traceId != null && !traceId.toString().isBlank()) {
                taskRegistry.deleteRequired(traceId.toString());
            }
        }
    }

    private static Map<String, Object> pendingApproval(Map<String, Object> snapshot) {
        Object value = snapshot.get("pendingApproval");
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                result.put(key.toString(), item);
            }
        });
        return result;
    }
}
