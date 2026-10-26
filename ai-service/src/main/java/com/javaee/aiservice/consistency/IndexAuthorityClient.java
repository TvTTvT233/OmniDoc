package com.javaee.aiservice.consistency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import static com.javaee.aiservice.consistency.IndexContracts.*;

@Component
public class IndexAuthorityClient {
    private final RestTemplate http;
    private final ObjectMapper mapper;
    private final String base;
    private final String token;
    public IndexAuthorityClient(ObjectMapper mapper,
            @Value("${document.service.url:http://document:8084}") String base,
            @Value("${omnidoc.index.internal-token:}") String token) {
        this.mapper = mapper; this.base = base; this.token = token;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(5000);
        this.http = new RestTemplate(factory);
    }
    public Task claim(String workerId) { return convert(call("/tasks/claim", Map.of("workerId", workerId)), Task.class); }
    public void renew(Task task) {
        if (!call("/tasks/" + task.taskId() + "/renew", Map.of("leaseToken", task.leaseToken())).path("renewed").asBoolean())
            throw new IndexConsistencyException("INDEX_LEASE_LOST");
    }
    public boolean complete(Task task, List<Segment> segments) {
        return call("/tasks/" + task.taskId() + "/complete", Map.of("leaseToken", task.leaseToken(),
                "generationId", Objects.toString(task.generationId(), ""), "contentHash", Objects.toString(task.contentHash(), ""),
                "configHash", Objects.toString(task.configHash(), ""), "segments", segments)).path("published").asBoolean();
    }
    public void fail(Task task, String code, boolean retryable) {
        call("/tasks/" + task.taskId() + "/fail", Map.of("leaseToken", task.leaseToken(), "errorCode", code, "retryable", retryable));
    }
    public List<VisibleDocument> visibility(String userId, String knowledgeBaseId, List<Candidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        if (candidates.size() > 100 || userId == null || userId.isBlank()) throw new IndexConsistencyException("INVALID_INDEX_SCOPE");
        Visibility result = convert(call("/visibility", Map.of("userId", userId, "knowledgeBaseId", knowledgeBaseId,
                "candidates", candidates)), Visibility.class);
        if (result == null || result.documents() == null) throw new IndexConsistencyException("INDEX_AUTHORITY_UNAVAILABLE");
        return result.documents();
    }
    private <T> T convert(JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) return null;
        try { return mapper.treeToValue(node, type); }
        catch (Exception e) { throw new IndexConsistencyException("INDEX_AUTHORITY_UNAVAILABLE"); }
    }
    private JsonNode call(String path, Object body) {
        if (token == null || token.length() < 32) throw new IndexConsistencyException("INDEX_INTERNAL_AUTH_DISABLED");
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-OmniDoc-Index-Token", token);
        try {
            ResponseEntity<JsonNode> response = http.postForEntity(base + "/internal/index" + path, new HttpEntity<>(body, headers), JsonNode.class);
            JsonNode envelope = response.getBody();
            if (envelope == null || envelope.path("code").asInt() != 200 || !envelope.has("data"))
                throw new IndexConsistencyException("INDEX_AUTHORITY_UNAVAILABLE");
            return envelope.get("data");
        } catch (IndexConsistencyException e) { throw e; }
        catch (Exception e) { throw new IndexConsistencyException("INDEX_AUTHORITY_UNAVAILABLE"); }
    }
}
