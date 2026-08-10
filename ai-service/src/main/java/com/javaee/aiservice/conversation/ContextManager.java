package com.javaee.aiservice.conversation;

import com.javaee.aiservice.conversation.runtime.LegacyRuntimeStateReader;
import com.javaee.aiservice.conversation.runtime.RuntimeStateScope;
import com.javaee.aiservice.conversation.runtime.RuntimeStateService;
import com.javaee.aiservice.conversation.runtime.RuntimeStateUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deprecated compatibility adapter around controlled Runtime State.
 *
 * <p>The legacy Map API is intentionally read-limited and write-filtered. New code must use
 * {@link RuntimeStateService} with an explicit conversation + trace scope.</p>
 */
@Component
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final RuntimeStateService runtimeStateService;
    private final LegacyRuntimeStateReader legacyReader;

    public ContextManager(RuntimeStateService runtimeStateService, LegacyRuntimeStateReader legacyReader) {
        this.runtimeStateService = runtimeStateService;
        this.legacyReader = legacyReader;
    }

    /**
     * 获取对话上下文
     * @param conversationId 对话ID
     * @return 上下文信息
     */
    public Map<String, Object> getContext(String conversationId) {
        LegacyRuntimeStateReader.LegacyReadResult result = legacyReader.readOptional(conversationId);
        if (result.availability() == LegacyRuntimeStateReader.Availability.DEGRADED_UNAVAILABLE) {
            log.warn("旧 Runtime State 兼容读取降级: conversationId={}", conversationId);
        }
        Map<String, Object> context = new LinkedHashMap<>(result.values());
        log.debug("读取白名单旧上下文: conversationId={}, size={}", conversationId, context.size());
        return context;
    }

    /**
     * 更新对话上下文
     * @param conversationId 对话ID
     * @param updates 更新内容
     */
    public void updateContext(String conversationId, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Object traceId = updates.get("traceId");
        if (!(traceId instanceof String trace) || trace.isBlank()) {
            log.debug("忽略无 traceId 的旧 Map 上下文写入: conversationId={}", conversationId);
            return;
        }
        RuntimeStateUpdate.Builder controlled = RuntimeStateUpdate.builder();
        String currentDocumentId = scalar(updates.get("currentDocumentId"));
        if (currentDocumentId == null) {
            currentDocumentId = scalar(updates.get("documentId"));
        }
        controlled.currentDocumentId(currentDocumentId)
                .selectedText(scalar(updates.get("selectedText")))
                .pageContext(scalar(updates.get("pageContext")));
        RuntimeStateUpdate update = controlled.build();
        runtimeStateService.update(new RuntimeStateScope(conversationId, trace), update);
        log.debug("旧 Map 上下文仅转写白名单标量: conversationId={}, traceId={}", conversationId, trace);
    }

    /**
     * 设置上下文字段
     * @param conversationId 对话ID
     * @param key 字段名
     * @param value 字段值
     */
    public void setContextValue(String conversationId, String key, Object value) {
        throw new UnsupportedOperationException(
                "Trace-less Runtime State writes are disabled; use RuntimeStateService"
        );
    }

    /**
     * 获取上下文字段
     * @param conversationId 对话ID
     * @param key 字段名
     * @return 字段值
     */
    public Object getContextValue(String conversationId, String key) {
        return getContext(conversationId).get(key);
    }

    /**
     * 清理对话上下文
     * @param conversationId 对话ID
     */
    public void clearContext(String conversationId) {
        runtimeStateService.clearConversation(conversationId);
        log.debug("清理 Runtime State: conversationId={}", conversationId);
    }

    /**
     * 合并上下文
     * @param conversationId 对话ID
     * @param newContext 新上下文
     */
    public void mergeContext(String conversationId, Map<String, Object> newContext) {
        updateContext(conversationId, newContext);
    }

    private static String scalar(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
