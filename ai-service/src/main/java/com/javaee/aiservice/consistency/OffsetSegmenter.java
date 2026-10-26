package com.javaee.aiservice.consistency;

import com.javaee.aiservice.rag.DocumentSegmenter;
import com.javaee.aiservice.rag.DocumentVectorizer;
import com.javaee.aiservice.rag.SegmentStrategy;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.javaee.aiservice.consistency.IndexContracts.*;

/** Exact ranges into authoritative canonical text, including semantic and hybrid strategies. */
@Component
public class OffsetSegmenter {
    private final DocumentVectorizer vectorizer;
    public OffsetSegmenter(DocumentVectorizer vectorizer) { this.vectorizer = vectorizer; }
    public List<StoredSegment> segment(Task task) {
        Config config = task.config();
        if (config == null || !"canonical-v1".equals(config.normalizationVersion()) || !"offset-v1".equals(config.segmenterVersion()))
            throw new IndexConsistencyException("INDEX_CONFIG_MISMATCH");
        int size = config.chunkSize(), overlap = config.overlap();
        if (size < 100 || size > 10000 || overlap < 0 || overlap >= size) throw new IndexConsistencyException("INVALID_INDEX_CONFIG");
        String text = task.canonicalText();
        if (text == null || text.isBlank()) throw new IndexConsistencyException("EMPTY_DOCUMENT");
        if (!hash(text).equals(task.contentHash())) throw new IndexConsistencyException("SNAPSHOT_HASH_MISMATCH");
        DocumentSegmenter.StrategyType strategy;
        try { strategy = DocumentSegmenter.StrategyType.valueOf(config.strategy()); }
        catch (Exception e) { throw new IndexConsistencyException("UNSUPPORTED_INDEX_STRATEGY"); }
        List<SegmentStrategy.Segment> raw = new DocumentSegmenter(vectorizer, size, overlap).segment(task.documentId(), text, strategy);
        // A short chapter or heading-only source still receives a verifiable segment.
        if (raw.isEmpty()) raw = new com.javaee.aiservice.rag.FixedLengthSegmentStrategy(size, overlap).segment(task.documentId(), text);
        if (raw.size() > 10000) throw new IndexConsistencyException("TOO_MANY_SEGMENTS");
        List<StoredSegment> segments = new ArrayList<>();
        for (SegmentStrategy.Segment value : raw) {
            int start = value.getStartOffset(), end = value.getEndOffset();
            if (start < 0 || end <= start || end > text.length() || !text.substring(start, end).equals(value.getContent()))
                throw new IndexConsistencyException("UNVERIFIABLE_SEGMENT_RANGE");
            Segment source = new Segment(UUID.randomUUID().toString(), task.generationId() + "_seg_" + segments.size(), start, end, hash(value.getContent()));
            segments.add(new StoredSegment(source, value.getContent()));
        }
        return List.copyOf(segments);
    }
}