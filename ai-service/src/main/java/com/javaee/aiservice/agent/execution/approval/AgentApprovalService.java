package com.javaee.aiservice.agent.execution.approval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side approval store for destructive Agent tools.
 */
@Service
public class AgentApprovalService {

    private static final String PREFIX = "agent:approval:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${ai.agent.approval-expiry-seconds:300}")
    private long approvalExpirySeconds;

    public ApprovalChallenge createChallenge(String userId, String toolName, Map<String, Object> params) {
        String token = UUID.randomUUID().toString();
        String fingerprint = fingerprint(userId, toolName, params);
        redisTemplate.opsForValue().set(PREFIX + token, fingerprint, Duration.ofSeconds(approvalExpirySeconds));
        return new ApprovalChallenge(token, System.currentTimeMillis() + approvalExpirySeconds * 1000);
    }

    public ApprovalResult verifyAndConsumeDetailed(String token, String userId, String toolName, Map<String, Object> params) {
        if (token == null || token.isBlank()) {
            return ApprovalResult.MISSING;
        }
        String key = PREFIX + token;
        String actual = fingerprint(userId, toolName, params);
        Long result=redisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "local v=redis.call('GET',KEYS[1]); if not v then return 0 end; if v~=ARGV[1] then return 2 end; redis.call('DEL',KEYS[1]); return 1",Long.class),
                java.util.List.of(key),actual);
        return Long.valueOf(1).equals(result)?ApprovalResult.OK:Long.valueOf(2).equals(result)?ApprovalResult.PARAMS_MISMATCH:ApprovalResult.EXPIRED_OR_USED;
    }

    public boolean verifyAndConsume(String token, String userId, String toolName, Map<String, Object> params) {
        return verifyAndConsumeDetailed(token, userId, toolName, params) == ApprovalResult.OK;
    }

    public boolean cancel(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.delete(PREFIX + token));
    }

    private String fingerprint(String userId, String toolName, Map<String, Object> params) {
        String raw = com.javaee.aiservice.taskrun.recovery.RecoveryJson.json(Map.of("owner",userId,"tool",toolName,"params",params));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成审批指纹失败", e);
        }
    }

    public record ApprovalChallenge(String token, long expiresAt) {
    }

    public enum ApprovalResult {
        OK,
        MISSING,
        EXPIRED_OR_USED,
        PARAMS_MISMATCH
    }
}
