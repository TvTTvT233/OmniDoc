package com.javaee.aiservice.consistency;

import com.javaee.aiservice.AiServiceApplication;
import com.javaee.aiservice.rag.DocumentVectorizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Explicit test-classpath launcher. Replaces only Embedding, never Agent planning. */
public final class ServiceFlowEvaluationApplication {
    public static void main(String[] args) {
        new SpringApplication(AiServiceApplication.class, SyntheticEmbedding.class).run(args);
    }

    @TestConfiguration
    public static class SyntheticEmbedding {
        @Bean @Primary DocumentVectorizer serviceFlowVectorizer() {
            return new DocumentVectorizer() {
                @Override public void assertAvailable() { }
                @Override public String modelName() { return "synthetic-embedding-v1"; }
                @Override public int configuredDimension() { return 16; }
                @Override public int getVectorDimension() { return 16; }
                @Override public float[] vectorize(String text) {
                    float[] result = new float[16];
                    text.codePoints().forEach(point -> result[Math.floorMod(point, 16)]++);
                    double norm = 0;
                    for (float value : result) norm += value * value;
                    if (norm == 0) result[0] = 1;
                    else for (int i = 0; i < result.length; i++) result[i] /= (float) Math.sqrt(norm);
                    return result;
                }
            };
        }
    }
}
