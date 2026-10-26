package com.javaee.aiservice.rag;

import com.javaee.aiservice.logging.SafeDiagnosticLogger;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import com.javaee.aiservice.consistency.IndexConsistencyException;

/**
 * 文档向量化器
 * 负责将文档内容转换为向量表示
 * 适配阿里云百炼dashscope的Embedding API
 */
@Component
public class DocumentVectorizer {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorizer.class);

    @Value("${spring.ai.dashscope.embedding.api-key:${spring.ai.dashscope.api-key}}")
    private String apiKey;

    @Value("${spring.ai.dashscope.embedding.model:text-embedding-v4}")
    private String model;

    @Value("${spring.ai.dashscope.embedding.dimension:1024}")
    private int dimension;

    @Value("${spring.ai.dashscope.embedding.enabled:false}")
    private boolean enabled;
    @Value("${spring.ai.dashscope.embedding.timeout-ms:15000}")
    private int timeoutMs;
    private final ExecutorService calls = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> { Thread t = new Thread(runnable, "embedding-http"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());

    public void assertAvailable() {
        if (!enabled) throw new IndexConsistencyException("EMBEDDING_DISABLED");
        if (apiKey == null || apiKey.isBlank()) throw new IndexConsistencyException("EMBEDDING_NOT_CONFIGURED");
    }
    public String modelName() { return model; }
    public int configuredDimension() { return dimension; }
    @PreDestroy public void shutdown() { calls.shutdownNow(); }

    /**
     * 将文本向量化
     * @param text 文本内容
     * @return 向量表示
     */
    public float[] vectorize(String text) {
        assertAvailable();
        Future<float[]> pending;
        try { pending = calls.submit(() -> vectorizeRemote(text)); }
        catch (RejectedExecutionException e) { throw new IndexConsistencyException("EMBEDDING_BUSY"); }
        try { return pending.get(Math.max(100, timeoutMs), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); pending.cancel(true); throw new IndexConsistencyException("EMBEDDING_INTERRUPTED"); }
        catch (TimeoutException e) { pending.cancel(true); throw new IndexConsistencyException("EMBEDDING_TIMEOUT"); }
        catch (ExecutionException e) { throw new IndexConsistencyException("EMBEDDING_FAILED"); }
    }

    private float[] vectorizeRemote(String text) {
        log.debug("开始向量化文本，长度={}", text.length());

        try {
            // 构建请求参数
            TextEmbeddingParam param = TextEmbeddingParam.builder()
                    .model(model)
                    .apiKey(apiKey)
                    .texts(Arrays.asList(text))
                    .build();

            // 创建模型实例并调用
            TextEmbedding textEmbedding = new TextEmbedding();
            TextEmbeddingResult result = textEmbedding.call(param);

            // 解析结果
            if (result != null && result.getOutput() != null 
                    && result.getOutput().getEmbeddings() != null 
                    && !result.getOutput().getEmbeddings().isEmpty()) {
                
                List<Double> embeddingList = result.getOutput().getEmbeddings().get(0).getEmbedding();
                float[] embedding = new float[embeddingList.size()];
                for (int i = 0; i < embeddingList.size(); i++) {
                    embedding[i] = embeddingList.get(i).floatValue();
                }

                log.debug("Embedding 向量解析完成，dimension={}", embedding.length);
                return embedding;
            }

            throw new RuntimeException("Embedding API返回结果为空");

        } catch (ApiException | NoApiKeyException e) {
            SafeDiagnosticLogger.error(log, "EMBEDDING_CALL_FAILED", "REMOTE_EMBEDDING_FAILED",
                    e, true, 0, 1, null, null);
            throw new RuntimeException("文档向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量向量化
     * @param texts 文本列表
     * @return 向量列表
     */
    public float[][] vectorizeBatch(String[] texts) {
        log.info("批量向量化，数量={}", texts.length);

        try {
            float[][] result = new float[texts.length][];
            for (int i = 0; i < texts.length; i++) {
                result[i] = vectorize(texts[i]);
            }
            log.info("批量向量化完成");
            return result;
        } catch (Exception e) {
            SafeDiagnosticLogger.error(log, "EMBEDDING_BATCH_FAILED", "REMOTE_EMBEDDING_FAILED",
                    e, true, 0, 1, null, null);
            throw new RuntimeException("批量向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取向量维度
     * @return 向量维度
     */
    public int getVectorDimension() {
        try {
            float[] sample = vectorize("test");
            return sample.length;
        } catch (Exception e) {
            SafeDiagnosticLogger.warn(log, "EMBEDDING_DIMENSION_DEGRADED",
                    "REMOTE_EMBEDDING_FAILED", e, true, 0, 1);
            return dimension;
        }
    }
}
