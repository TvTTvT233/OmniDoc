package com.javaee.aiservice.consistency;

import com.javaee.aiservice.rag.DocumentVectorizer;
import com.javaee.aiservice.rag.VectorStore;
import com.javaee.aiservice.rag.vector.contract.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import static com.javaee.aiservice.consistency.IndexContracts.*;

@Component
public class IndexGenerationWorker {
    private final IndexAuthorityClient authority;
    private final GenerationStore store;
    private final OffsetSegmenter segmenter;
    private final DocumentVectorizer vectorizer;
    private final VectorStore vectors;
    private final boolean enabled;
    private final String workerId = "ai-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "index-lease-renewal"); thread.setDaemon(true); return thread;
    });
    @PreDestroy public void shutdown() { heartbeats.shutdownNow(); }
    public IndexGenerationWorker(IndexAuthorityClient authority, GenerationStore store, OffsetSegmenter segmenter,
            DocumentVectorizer vectorizer, VectorStore vectors, @Value("${omnidoc.index.worker-enabled:false}") boolean enabled) {
        this.authority = authority; this.store = store; this.segmenter = segmenter; this.vectorizer = vectorizer; this.vectors = vectors; this.enabled = enabled;
    }
    @Scheduled(fixedDelayString = "${omnidoc.index.poll-ms:5000}")
    public void poll() {
        if (!enabled) return;
        try { store.reclaimRetired(); } catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Index cleanup retry remains pending"); }
        try { Task task = authority.claim(workerId); if (task != null) run(task); }
        catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Index worker poll unavailable: {}", e.getClass().getSimpleName()); }
    }
    public void run(Task task) {
        AtomicBoolean lost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
            try { authority.renew(task); } catch (Exception e) { lost.set(true); }
        }, 10, 10, TimeUnit.SECONDS);
        try {
            authority.renew(task);
            if ("CLEANUP".equals(task.operation())) {
                for (String generation : task.cleanupGenerationIds() == null ? List.<String>of() : task.cleanupGenerationIds()) {
                    authority.renew(task);
                    store.cleanup(generation, task.documentId(), task.ownerUserId(), task.knowledgeBaseId());
                }
                authority.complete(task, List.of());
                return;
            }
            if (!"BUILD".equals(task.operation())) throw new IndexConsistencyException("INVALID_INDEX_OPERATION");
            vectorizer.assertAvailable();
            if (!vectorizer.modelName().equals(task.config().embeddingModel()) || vectorizer.configuredDimension() != task.config().embeddingDimension())
                throw new IndexConsistencyException("INDEX_CONFIG_MISMATCH");
            List<StoredSegment> segments = segmenter.segment(task);
            if (lost.get()) throw new IndexConsistencyException("INDEX_LEASE_LOST");
            Manifest manifest = new Manifest(task.documentId(), task.documentVersion(), task.generationId(), task.ownerUserId(),
                    task.knowledgeBaseId(), task.contentHash(), task.configHash(), task.config(), segments);
            authority.renew(task);
            store.create(manifest);
            VectorScope scope = new VectorScope(task.ownerUserId(), task.knowledgeBaseId());
            EmbeddingSpec embedding = new EmbeddingSpec(task.config().embeddingModel(), task.config().embeddingDimension(), task.config().schemaVersion());
            for (StoredSegment segment : segments) {
                if (lost.get()) throw new IndexConsistencyException("INDEX_LEASE_LOST");
                authority.renew(task);
                float[] vector = vectorizer.vectorize(segment.content());
                // Embedding may have crossed a lease deadline. Never write or publish with the old lease.
                authority.renew(task);
                store.assertWritable(task.generationId());
                vectors.upsert(new VectorRecord(segment.source().segmentId(), task.documentId(), vector, scope, embedding,
                        Map.of("generationId", task.generationId(), "documentVersion", task.documentVersion(),
                                "contentHash", task.contentHash(), "configHash", task.configHash())));
                try { store.assertWritable(task.generationId()); }
                catch (IndexConsistencyException retired) { vectors.deleteById(segment.source().segmentId(), scope); throw retired; }
            }
            authority.renew(task);
            if (!authority.complete(task, segments.stream().map(StoredSegment::source).toList()))
                throw new IndexConsistencyException("INDEX_PUBLICATION_REJECTED");
        } catch (Exception e) {
            String code = e instanceof IndexConsistencyException error ? error.code() : "INDEX_BUILD_FAILED";
            try { authority.fail(task, code, !Set.of("UNSUPPORTED_INDEX_STRATEGY", "EMPTY_DOCUMENT", "SNAPSHOT_HASH_MISMATCH").contains(code)); }
            catch (RuntimeException ignored) { /* Expired leases are reclaimed by the durable authority, never locally acknowledged. */ }
            LoggerFactory.getLogger(getClass()).warn("Index attempt failed: task={}, code={}", task.taskId(), code);
        } finally { heartbeat.cancel(false); }
    }
}
