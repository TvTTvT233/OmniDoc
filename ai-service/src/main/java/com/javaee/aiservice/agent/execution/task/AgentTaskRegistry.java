package com.javaee.aiservice.agent.execution.task;

import com.javaee.aiservice.conversation.runtime.RuntimeStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 简易任务运行注册表，保留最近一段时间的 Agent 任务执行快照，
 * 支持按 traceId 查询、按 userId 列出、取消标记等操作。
 * 生产环境建议改造为持久化存储（DB / Redis）。
 */
@Component
public class AgentTaskRegistry {

    private static final int MAX_TASKS = 200;
    private static final long TASK_TTL_DAYS = 7L;
    private static final String TASK_KEY_PREFIX = "agent:task:";
    private static final String TASK_ALL_ZSET = "agent:tasks:all";
    private static final String TASK_USER_ZSET_PREFIX = "agent:tasks:user:";

    private final ConcurrentMap<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();
    private final java.util.Deque<String> evictionOrder = new java.util.concurrent.ConcurrentLinkedDeque<>();

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    public void save(String traceId, Map<String, Object> snapshot) {
        if (traceId == null || snapshot == null) {
            return;
        }
        Map<String, Object> stored = new LinkedHashMap<>(snapshot);
        stored.putIfAbsent("traceId", traceId);
        stored.putIfAbsent("savedAt", System.currentTimeMillis());
        tasks.put(traceId, stored);
        evictionOrder.addLast(traceId);
        persist(traceId, stored);
        while (evictionOrder.size() > MAX_TASKS) {
            String oldest = evictionOrder.pollFirst();
            if (oldest != null) {
                tasks.remove(oldest);
            }
        }
    }

    public Map<String, Object> get(String traceId) {
        Map<String, Object> local = tasks.get(traceId);
        if (local != null) {
            return local;
        }
        Map<String, Object> persisted = load(traceId);
        if (!persisted.isEmpty()) {
            tasks.put(traceId, persisted);
            return persisted;
        }
        return Collections.emptyMap();
    }

    public Map<String, Object> findByApprovalToken(String token) {
        if (token == null || token.isBlank()) {
            return Collections.emptyMap();
        }
        for (Map<String, Object> snapshot : tasks.values()) {
            Object pending = snapshot.get("pendingApproval");
            if (pending instanceof Map<?, ?> map && token.equals(String.valueOf(map.get("agentApprovalToken")))) {
                return snapshot;
            }
        }
        for (Map<String, Object> snapshot : listAll()) {
            Object pending = snapshot.get("pendingApproval");
            if (pending instanceof Map<?, ?> map && token.equals(String.valueOf(map.get("agentApprovalToken")))) {
                Map<String, Object> loaded = new LinkedHashMap<>(snapshot);
                tasks.put(String.valueOf(loaded.get("traceId")), loaded);
                return loaded;
            }
        }
        return Collections.emptyMap();
    }

    public List<Map<String, Object>> listByUser(String userId) {
        if (userId == null) {
            return List.of();
        }
        List<Map<String, Object>> persisted = listFromRedis(TASK_USER_ZSET_PREFIX + userId);
        if (!persisted.isEmpty()) {
            return persisted;
        }
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> snapshot : tasks.values()) {
            if (userId.equals(snapshot.get("userId"))) {
                matched.add(snapshot);
            }
        }
        matched.sort((a, b) -> Long.compare(asLong(b.get("savedAt")), asLong(a.get("savedAt"))));
        return matched;
    }

    public boolean cancel(String traceId) {
        Map<String, Object> snapshot = tasks.get(traceId);
        if (snapshot == null) {
            return false;
        }
        Object status = snapshot.get("status");
        if ("success".equals(status) || "cancelled".equals(status) || "error".equals(status)) {
            return false;
        }
        snapshot.put("status", "cancelled");
        snapshot.put("cancelledAt", System.currentTimeMillis());
        persist(traceId, snapshot);
        return true;
    }

    public boolean isCancelled(String traceId) {
        Map<String, Object> snapshot = tasks.get(traceId);
        return snapshot != null && "cancelled".equals(snapshot.get("status"));
    }

    /**
     * 列出全部任务（按 savedAt 倒序）。供管理员/调试使用。
     */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> persisted = listFromRedis(TASK_ALL_ZSET);
        if (!persisted.isEmpty()) {
            return persisted;
        }
        List<Map<String, Object>> all = new ArrayList<>(tasks.values());
        all.sort((a, b) -> Long.compare(asLong(b.get("savedAt")), asLong(a.get("savedAt"))));
        return all;
    }

    /**
     * 删除指定任务快照。
     */
    public boolean delete(String traceId) {
        Map<String, Object> removed = tasks.remove(traceId);
        boolean redisRemoved = deletePersisted(traceId);
        if (removed != null || redisRemoved) {
            evictionOrder.remove(traceId);
            return true;
        }
        return false;
    }

    /**
     * Returns every local or persisted task snapshot associated with a Conversation.
     * Redis failures are not degraded to an empty list because this method is used by permanent
     * Conversation deletion.
     */
    public List<Map<String, Object>> findByConversationIdRequired(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        Map<String, Map<String, Object>> matched = new LinkedHashMap<>();
        for (Map<String, Object> snapshot : tasks.values()) {
            addConversationSnapshot(matched, conversationId, snapshot);
        }
        if (redisTemplate != null) {
            for (Map<String, Object> snapshot : listFromRedisRequired(TASK_ALL_ZSET)) {
                addConversationSnapshot(matched, conversationId, snapshot);
            }
        }
        return List.copyOf(matched.values());
    }

    /**
     * Deletes a task snapshot for a required lifecycle operation. Unlike the compatibility delete
     * method, a Redis failure is explicit and cannot be mistaken for successful cleanup.
     */
    public void deleteRequired(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (redisTemplate != null) {
            deletePersistedRequired(traceId);
        }
        tasks.remove(traceId);
        evictionOrder.remove(traceId);
    }

    private void persist(String traceId, Map<String, Object> snapshot) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String key = TASK_KEY_PREFIX + traceId;
            long score = asLong(snapshot.get("savedAt"));
            redisTemplate.opsForValue().set(key, snapshot, TASK_TTL_DAYS, TimeUnit.DAYS);
            redisTemplate.opsForZSet().add(TASK_ALL_ZSET, traceId, score);
            Object userId = snapshot.get("userId");
            if (userId != null) {
                redisTemplate.opsForZSet().add(TASK_USER_ZSET_PREFIX + userId, traceId, score);
            }
        } catch (Exception ignored) {
            // Redis 不可用时仍保留内存快照，避免影响主链路。
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(String traceId) {
        if (redisTemplate == null || traceId == null) {
            return Collections.emptyMap();
        }
        try {
            Object stored = redisTemplate.opsForValue().get(TASK_KEY_PREFIX + traceId);
            if (stored instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyMap();
    }

    private List<Map<String, Object>> listFromRedis(String zsetKey) {
        if (redisTemplate == null) {
            return List.of();
        }
        try {
            Set<Object> ids = redisTemplate.opsForZSet().reverseRange(zsetKey, 0, MAX_TASKS - 1);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> snapshots = new ArrayList<>();
            for (Object id : ids) {
                Map<String, Object> item = load(String.valueOf(id));
                if (!item.isEmpty()) {
                    snapshots.add(item);
                }
            }
            snapshots.sort((a, b) -> Long.compare(asLong(b.get("savedAt")), asLong(a.get("savedAt"))));
            return snapshots;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> listFromRedisRequired(String zsetKey) {
        try {
            // Permanent deletion must not inherit the normal UI list limit; otherwise an older
            // still-live task could keep a deleted Conversation reachable until its seven-day TTL.
            Set<Object> ids = redisTemplate.opsForZSet().reverseRange(zsetKey, 0, -1);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> snapshots = new ArrayList<>();
            for (Object id : new LinkedHashSet<>(ids)) {
                Object stored = redisTemplate.opsForValue().get(TASK_KEY_PREFIX + id);
                if (stored instanceof Map<?, ?> raw) {
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    raw.forEach((key, value) -> {
                        if (key != null) {
                            snapshot.put(key.toString(), value);
                        }
                    });
                    snapshots.add(snapshot);
                }
            }
            return snapshots;
        } catch (Exception error) {
            throw new RuntimeStateException.Unavailable("agent task cleanup scan", error);
        }
    }

    private void deletePersistedRequired(String traceId) {
        try {
            Object stored = redisTemplate.opsForValue().get(TASK_KEY_PREFIX + traceId);
            Object userId = stored instanceof Map<?, ?> snapshot ? snapshot.get("userId") : null;
            redisTemplate.delete(TASK_KEY_PREFIX + traceId);
            redisTemplate.opsForZSet().remove(TASK_ALL_ZSET, traceId);
            if (userId != null) {
                redisTemplate.opsForZSet().remove(TASK_USER_ZSET_PREFIX + userId, traceId);
            }
        } catch (Exception error) {
            throw new RuntimeStateException.Unavailable("agent task cleanup delete", error);
        }
    }

    private static void addConversationSnapshot(
            Map<String, Map<String, Object>> matched,
            String conversationId,
            Map<String, Object> snapshot
    ) {
        if (!conversationId.equals(String.valueOf(snapshot.get("conversationId")))) {
            return;
        }
        Object traceId = snapshot.get("traceId");
        if (traceId != null && !traceId.toString().isBlank()) {
            matched.put(traceId.toString(), snapshot);
        }
    }

    private boolean deletePersisted(String traceId) {
        if (redisTemplate == null || traceId == null) {
            return false;
        }
        try {
            Map<String, Object> snapshot = load(traceId);
            redisTemplate.delete(TASK_KEY_PREFIX + traceId);
            redisTemplate.opsForZSet().remove(TASK_ALL_ZSET, traceId);
            Object userId = snapshot.get("userId");
            if (userId != null) {
                redisTemplate.opsForZSet().remove(TASK_USER_ZSET_PREFIX + userId, traceId);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
