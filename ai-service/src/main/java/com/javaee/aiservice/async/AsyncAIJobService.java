package com.javaee.aiservice.async;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.config.AiRabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Stores async job state and publishes model requests to RabbitMQ.
 */
@Service
public class AsyncAIJobService {

    private static final String JOB_PREFIX = "ai:async:job:";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired(required=false)
    private com.javaee.aiservice.taskrun.recovery.AgentRunStore durableStore;
    @Autowired
    private com.javaee.aiservice.agent.execution.AgentExecutionService agentExecutionService;

    @Value("${ai.async.job-expiry-hours:24}")
    private long jobExpiryHours;

    public AsyncAIJobVO submit(String type, Object payload, String model, String userId) {
        String jobId = UUID.randomUUID().toString();
        if(durableStore!=null && "agent".equals(type)) {
            var request=objectMapper.convertValue(payload,com.javaee.aiservice.agent.execution.model.AgentExecutionRequest.class);
            request.setUserId(userId);
            if(model!=null && !model.isBlank()) request.setModel(model);
            var run=agentExecutionService.submitDurable(request,jobId);
            AsyncAIJobMessage wake=new AsyncAIJobMessage(); wake.setJobId(run.asyncJobId()); wake.setType("agent");
            // The database is the queue. Rabbit is a latency optimization, never the payload authority.
            try { rabbitTemplate.convertAndSend(AiRabbitMQConfig.AI_EXCHANGE,AiRabbitMQConfig.AI_MODEL_REQUEST_ROUTING_KEY,wake); }
            catch(org.springframework.amqp.AmqpException unavailable) { /* poller will claim the committed run */ }
            return durableJob(run);
        }
        long now = System.currentTimeMillis();

        AsyncAIJobVO job = new AsyncAIJobVO();
        job.setJobId(jobId);
        job.setType(type);
        job.setModel(model);
        job.setUserId(userId);
        job.setStatus("queued");
        job.setSubmittedAt(now);
        save(job);

        AsyncAIJobMessage message = new AsyncAIJobMessage();
        message.setJobId(jobId);
        message.setType(type);
        message.setModel(model);
        message.setUserId(userId);
        message.setCreatedAt(now);
        message.setPayload(toMap(payload));

        rabbitTemplate.convertAndSend(
                AiRabbitMQConfig.AI_EXCHANGE,
                AiRabbitMQConfig.AI_MODEL_REQUEST_ROUTING_KEY,
                message
        );

        return job;
    }

    public AsyncAIJobVO getJob(String jobId, String userId, boolean admin) {
        AsyncAIJobVO job = load(jobId);
        if (job == null) {
            throw new IllegalArgumentException("异步任务不存在: " + jobId);
        }
        if (!admin && !userId.equals(job.getUserId())) {
            throw new SecurityException("无权访问该异步任务");
        }
        return job;
    }

    public void markRunning(String jobId) {
        AsyncAIJobVO job = requireJob(jobId);
        job.setStatus("running");
        job.setStartedAt(System.currentTimeMillis());
        save(job);
    }

    public java.util.Optional<AsyncAIJobVO> findOwned(String jobId, String userId) {
        AsyncAIJobVO job = load(jobId);
        return job != null && userId != null && userId.equals(job.getUserId())
                ? java.util.Optional.of(job) : java.util.Optional.empty();
    }

    public void bindTrace(String jobId, String traceId) {
        AsyncAIJobVO job = requireJob(jobId);
        job.setTraceId(traceId);
        save(job);
    }

    public void markSuccess(String jobId, Object result) {
        AsyncAIJobVO job = requireJob(jobId);
        job.setStatus("success");
        job.setResult(result);
        job.setFinishedAt(System.currentTimeMillis());
        save(job);
    }

    public void markFailed(String jobId, String error) {
        AsyncAIJobVO job = requireJob(jobId);
        job.setStatus("failed");
        job.setError(error);
        job.setFinishedAt(System.currentTimeMillis());
        save(job);
    }

    private AsyncAIJobVO requireJob(String jobId) {
        AsyncAIJobVO job = load(jobId);
        if (job == null) {
            throw new IllegalArgumentException("异步任务不存在: " + jobId);
        }
        return job;
    }

    private AsyncAIJobVO load(String jobId) {
        if(durableStore!=null) {
            var run=durableStore.findAsync(jobId);
            if(run.isPresent()) return durableJob(run.get());
        }
        Object value = redisTemplate.opsForValue().get(JOB_PREFIX + jobId);
        if (value instanceof AsyncAIJobVO job) {
            return job;
        }
        return objectMapper.convertValue(value, AsyncAIJobVO.class);
    }

    private AsyncAIJobVO durableJob(com.javaee.aiservice.taskrun.recovery.AgentRunStore.Run run) {
        AsyncAIJobVO job=new AsyncAIJobVO(); job.setJobId(run.asyncJobId()); job.setType("agent");
        job.setUserId(run.owner()); job.setTraceId(run.id());
        job.setStatus(switch(run.status()) { case "QUEUED" -> "queued"; case "RUNNING" -> "running"; default -> "success"; });
        var result=agentExecutionService.durableSnapshot(run);result.put("runtimePersistence","JDBC");job.setResult(result);
        return job;
    }

    private void save(AsyncAIJobVO job) {
        redisTemplate.opsForValue().set(
                JOB_PREFIX + job.getJobId(),
                job,
                Duration.ofHours(jobExpiryHours)
        );
    }

    private Map<String, Object> toMap(Object payload) {
        if (payload == null) {
            return Map.of();
        }
        return objectMapper.convertValue(payload, new TypeReference<>() {});
    }
}
