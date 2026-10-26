package com.javaee.aiservice.consistency;

import com.javaee.aiservice.AiServiceApplication;
import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.rag.DocumentVectorizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit test-classpath entry point. Never packaged in the production JAR. */
public final class SyntheticConsistencyApplication {
    public static void main(String[] args) {
        new SpringApplication(AiServiceApplication.class, SyntheticConfiguration.class).run(args);
    }
    @TestConfiguration
    public static class SyntheticConfiguration {
        @Bean @Primary DocumentVectorizer syntheticVectorizer(Environment env) {
            return new DocumentVectorizer() {
                private final AtomicInteger calls = new AtomicInteger();
                @Override public void assertAvailable() {}
                @Override public String modelName() { return "synthetic-embedding-v1"; }
                @Override public int configuredDimension() { return 16; }
                @Override public int getVectorDimension() { return 16; }
                @Override public float[] vectorize(String text) {
                    int call = calls.incrementAndGet();
                    int failAt = env.getProperty("synthetic.embedding.fail-at", Integer.class, -1);
                    if (call == failAt) throw new IndexConsistencyException("SYNTHETIC_INJECTED_FAILURE");
                    long delay = env.getProperty("synthetic.embedding.delay-ms", Long.class, 0L);
                    if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IndexConsistencyException("SYNTHETIC_INTERRUPTED"); }
                    float[] vector = new float[16];
                    for (int point : text.codePoints().toArray()) vector[Math.floorMod(point, 16)] += 1;
                    double norm = 0; for (float v : vector) norm += v * v;
                    if (norm == 0) vector[0] = 1;
                    else for (int i = 0; i < vector.length; i++) vector[i] /= (float)Math.sqrt(norm);
                    return vector;
                }
            };
        }
        @Bean @Primary ChatService syntheticChatService() {
            return new ChatService() {
                @Override public String callChatApiWithModelCode(String prompt, String model) {
                    if (prompt.contains("任务规划器")) return "[{\"id\":\"retrieve\",\"description\":\"synthetic retrieval\",\"toolName\":\"rag-answer\",\"params\":{},\"reasoning\":\"test fixture\"}]";
                    if (prompt.contains("continue")) return "{\"continue\":false}";
                    return "[SYNTHETIC TEST ANSWER] 本回复仅用于验证引用与持久化链路，不代表真实模型效果。";
                }
                @Override public String callChatApi(String prompt) { return callChatApiWithModelCode(prompt, null); }
            };
        }
    }
}
