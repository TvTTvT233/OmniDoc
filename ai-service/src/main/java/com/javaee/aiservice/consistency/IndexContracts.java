package com.javaee.aiservice.consistency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Wire contract shared with document-service's transactional index authority. */
public final class IndexContracts {
    private IndexContracts() {}
    public record Config(String normalizationVersion, String segmenterVersion, String strategy,
                         int chunkSize, int overlap, String embeddingModel, int embeddingDimension, int schemaVersion) {}
    public record Task(String taskId, String leaseToken, String leaseExpiresAt, String operation,
                       String documentId, long documentVersion, String generationId, String ownerUserId,
                       String knowledgeBaseId, String contentHash, String configHash, String canonicalText,
                       String fileId, String documentTitle, Config config, List<String> cleanupGenerationIds) {}
    public record Candidate(String documentId, long documentVersion, String generationId) {}
    public record Reference(String referenceId, String documentId, long documentVersion, String documentTitle,
                            String segmentId, String generationId, String contentHash,
                            int startOffset, int endOffset, String segmentHash) {}
    public record Segment(String referenceId, String segmentId, int startOffset, int endOffset, String segmentHash) {}
    public record StoredSegment(Segment source, String content) {}
    public record Manifest(String documentId, long documentVersion, String generationId, String ownerUserId,
                           String knowledgeBaseId, String contentHash, String configHash, Config config,
                           List<StoredSegment> segments) {
        public Candidate candidate() { return new Candidate(documentId, documentVersion, generationId); }
    }
    public record VisibleDocument(String documentId, long documentVersion, String generationId, String ownerUserId,
                                  String knowledgeBaseId, String contentHash, String configHash, String documentTitle,
                                  String fileId, List<Reference> references) {}
    public record Visibility(List<VisibleDocument> documents) {}
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
