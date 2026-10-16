package com.javaee.aiservice.rag;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.util.*;

/** Strict, read-only view of stored segments. It does not certify vector indexing success. */
@Service
public class DocumentSegmentReadService {
    public record View(String indexState, List<Map<String, Object>> segments) {}

    private final RedisTemplate<String, Object> redis;

    public DocumentSegmentReadService(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    public View read(String documentId, String userId) {
        try {
            return redis.execute(new SessionCallback<View>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public View execute(RedisOperations operations) {
                    RedisOperations<String, Object> ops = operations;
                    try {
                        String documentKey = "doc:" + documentId;
                        String listKey = "doc_segments:" + documentId;
                        String contentKey = "content:" + documentId;
                        ops.watch(List.of(documentKey, listKey, contentKey));
                        Map<Object, Object> metadata = ops.opsForHash().entries(documentKey);
                        Object rawIds = ops.opsForValue().get(listKey);
                        View view;
                        if (metadata.isEmpty()) {
                            if (rawIds != null || Boolean.TRUE.equals(ops.hasKey(contentKey))) throw incomplete();
                            view = new View("NOT_INDEXED", List.of());
                        } else {
                            Object owner = metadata.get("userId");
                            if (owner == null || owner.toString().isBlank()) throw incomplete();
                            if (!userId.equals(owner.toString())) {
                                throw new SegmentReadException(403, "无权读取该索引分段");
                            }
                            if (!(rawIds instanceof List<?> ids) || ids.isEmpty() || ids.size() > 10000) {
                                throw incomplete();
                            }
                            List<String> keys = new ArrayList<>();
                            Set<String> uniqueIds = new HashSet<>();
                            String segmentPattern = java.util.regex.Pattern.quote(documentId) + "_(seg|chapter|semantic)_[0-9]+";
                            for (Object id : ids) {
                                if (!(id instanceof String value) || !value.matches(segmentPattern) || !uniqueIds.add(value)) {
                                    throw incomplete();
                                }
                                keys.add("segment:" + value);
                            }
                            ops.watch(keys);
                            List<Map<String, Object>> segments = new ArrayList<>();
                            for (int i = 0; i < ids.size(); i++) {
                                Object content = ops.opsForValue().get(keys.get(i));
                                if (!(content instanceof String)) throw incomplete();
                                segments.add(Map.of("segmentId", ids.get(i), "content", content));
                            }
                            view = new View("SEGMENTS_AVAILABLE", List.copyOf(segments));
                        }
                        // Empty read-only transaction validates WATCH: a concurrent index rewrite is not returned.
                        ops.multi();
                        if (ops.exec() == null) throw incomplete();
                        return view;
                    } finally {
                        ops.unwatch();
                    }
                }
            });
        } catch (SegmentReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SegmentReadException(503, "分段存储暂不可用，请稍后重试");
        }
    }

    private static SegmentReadException incomplete() {
        return new SegmentReadException(409, "分段数据不完整或正在更新，请稍后重试");
    }
}
