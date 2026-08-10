package com.javaee.aiservice.conversation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class RedisLegacyConversationSource implements LegacyConversationSource {

    private static final Logger log = LoggerFactory.getLogger(RedisLegacyConversationSource.class);
    private static final String CONVERSATION_PREFIX = "conv:";

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisLegacyConversationSource(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<LegacyConversationSnapshot> findOwned(String conversationId, String userId) {
        String key = CONVERSATION_PREFIX + conversationId;
        try {
            Object owner = redisTemplate.opsForHash().get(key, "userId");
            if (owner == null || !userId.equals(String.valueOf(owner))) {
                return Optional.empty();
            }
            Object rawMessages = redisTemplate.opsForHash().get(key, "messages");
            if (!(rawMessages instanceof List<?> values)) {
                return Optional.empty();
            }
            List<String> messages = new ArrayList<>(values.size());
            for (Object value : values) {
                messages.add(String.valueOf(value));
            }
            return Optional.of(new LegacyConversationSnapshot(conversationId, userId, messages));
        } catch (DataAccessException error) {
            log.warn("Legacy conversation source is unavailable for conversationId={}", conversationId);
            return Optional.empty();
        }
    }
}
