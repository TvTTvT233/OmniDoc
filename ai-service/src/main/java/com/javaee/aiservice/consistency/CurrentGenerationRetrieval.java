package com.javaee.aiservice.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.rag.*;
import com.javaee.aiservice.rag.vector.contract.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;
import static com.javaee.aiservice.consistency.IndexContracts.*;

/** All candidate text crosses the authoritative current-generation/ACL gate before ranking or generation. */
@Component
public class CurrentGenerationRetrieval {
    private final GenerationStore store;
    private final IndexAuthorityClient authority;
    private final DocumentVectorizer vectorizer;
    private final VectorStore vectors;
    private final Reranker reranker;
    private final ObjectMapper mapper;
    public CurrentGenerationRetrieval(GenerationStore store, IndexAuthorityClient authority, DocumentVectorizer vectorizer,
            VectorStore vectors, Reranker reranker, ObjectMapper mapper) {
        this.store = store; this.authority = authority; this.vectorizer = vectorizer; this.vectors = vectors; this.reranker = reranker; this.mapper = mapper;
    }
    private List<VisibleDocument> visible(List<Manifest> candidates, String user, String kb) {
        List<VisibleDocument> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i += 100)
            result.addAll(authority.visibility(user, kb, candidates.subList(i, Math.min(i + 100, candidates.size())).stream().map(Manifest::candidate).toList()));
        return result;
    }
    public List<Map<String, Object>> search(String query, int topK, Map<String, Object> filters, boolean hybrid, Reranker.RerankStrategy strategy) {
        String user = text(filters.get("userId")), kb = text(filters.get("knowledgeBaseId"));
        if (user.isBlank() || kb.isBlank()) throw new IndexConsistencyException("INVALID_INDEX_SCOPE");
        List<Manifest> candidates = store.candidates(user, kb);
        Map<String, Manifest> byGeneration = candidates.stream().collect(Collectors.toMap(Manifest::generationId, m -> m));
        List<VisibleDocument> visible = visible(candidates, user, kb);
        // Explicit bound; never silently drop documents or let old generations crowd out current candidates.
        if (visible.size() > 100) throw new IndexConsistencyException("INDEX_SCOPE_TOO_LARGE");
        if (visible.isEmpty()) throw new IndexConsistencyException("INDEX_NOT_READY");
        float[] queryVector = vectorizer.vectorize(query);
        Map<String, Map<String, Object>> ranked = new LinkedHashMap<>();
        int limit = Math.min(100, Math.max(1, topK * 3));
        for (VisibleDocument doc : visible) {
            Manifest manifest = byGeneration.get(doc.generationId());
            if (!matches(manifest, doc, user, kb)) throw new IndexConsistencyException("INDEX_MANIFEST_MISMATCH");
            Map<String, Reference> refs = doc.references().stream().collect(Collectors.toMap(Reference::segmentId, r -> r));
            Map<String, StoredSegment> segments = manifest.segments().stream().collect(Collectors.toMap(s -> s.source().segmentId(), s -> s));
            Config config = manifest.config();
            if (!config.embeddingModel().equals(vectorizer.modelName()) || config.embeddingDimension() != queryVector.length)
                throw new IndexConsistencyException("INDEX_CONFIG_MISMATCH");
            List<VectorMatch> hits = vectors.search(new VectorQuery(queryVector, limit,
                    new VectorFilter(new VectorScope(user, kb), doc.documentId(), Map.of("generationId", doc.generationId())),
                    new EmbeddingSpec(config.embeddingModel(), config.embeddingDimension(), config.schemaVersion())));
            for (VectorMatch hit : hits) {
                StoredSegment segment = segments.get(hit.id()); Reference ref = refs.get(hit.id());
                if (!doc.documentId().equals(hit.documentId()) || !valid(segment, ref, doc)) throw new IndexConsistencyException("INDEX_SEGMENT_MISMATCH");
                ranked.put(hit.id(), row(segment, ref, hit.score(), "vector"));
            }
            if (hybrid) for (StoredSegment segment : manifest.segments()) {
                Reference ref = refs.get(segment.source().segmentId());
                if (!valid(segment, ref, doc)) throw new IndexConsistencyException("INDEX_SEGMENT_MISMATCH");
                float score = lexical(query, segment.content());
                if (score > 0) ranked.putIfAbsent(segment.source().segmentId(), row(segment, ref, score, "bm25"));
            }
        }
        List<Map<String, Object>> results = ranked.values().stream().sorted((a,b) -> Float.compare(((Number)b.get("similarity")).floatValue(), ((Number)a.get("similarity")).floatValue())).limit(limit).collect(Collectors.toCollection(ArrayList::new));
        if (strategy != null) results = reranker.rerank(query, results, strategy, Math.max(1, Math.min(topK, 100)));
        else results = new ArrayList<>(results.subList(0, Math.min(Math.max(1, topK), results.size())));
        List<Reference> accepted = validateReferences(results.stream().map(r -> (Reference)r.get("reference")).toList(), user, kb);
        if (accepted.size() != results.size()) throw new IndexConsistencyException("INDEX_NOT_READY");
        Set<String> allowed = accepted.stream().map(Reference::referenceId).collect(Collectors.toSet());
        return results.stream().filter(r -> allowed.contains(((Reference)r.get("reference")).referenceId())).toList();
    }
    public List<Reference> validateReferences(List<Reference> supplied, String user, String kb) {
        if (supplied == null || supplied.isEmpty()) return List.of();
        if (supplied.size() > 100) throw new IndexConsistencyException("INDEX_REFERENCE_LIMIT");
        List<Candidate> candidates = supplied.stream().map(r -> new Candidate(r.documentId(), r.documentVersion(), r.generationId())).distinct().toList();
        if (candidates.size() > 100) throw new IndexConsistencyException("INDEX_SCOPE_TOO_LARGE");
        Map<String, Reference> allowed = new HashMap<>();
        for (VisibleDocument doc : authority.visibility(user, kb, candidates)) {
            if (!user.equals(doc.ownerUserId()) || !kb.equals(doc.knowledgeBaseId())) continue;
            for (Reference ref : doc.references()) allowed.put(ref.referenceId(), ref);
        }
        return supplied.stream().filter(ref -> ref.equals(allowed.get(ref.referenceId()))).distinct().toList();
    }
    public List<Reference> references(List<Map<String,Object>> results) {
        return results.stream().map(row -> row.get("reference")).filter(Reference.class::isInstance).map(Reference.class::cast).distinct().toList();
    }
    public Reference parseReference(Object value) {
        try { return mapper.convertValue(value, Reference.class); } catch (Exception e) { return null; }
    }
    public List<Map<String,Object>> segments(String documentId, String user, String kb) {
        List<Manifest> manifests = store.candidates(user, kb).stream().filter(m -> documentId.equals(m.documentId())).toList();
        List<VisibleDocument> docs = visible(manifests, user, kb);
        List<Map<String,Object>> output = new ArrayList<>();
        for (VisibleDocument doc : docs) {
            Manifest manifest = manifests.stream().filter(m -> m.generationId().equals(doc.generationId())).findFirst().orElseThrow();
            if (!matches(manifest, doc, user, kb)) throw new IndexConsistencyException("INDEX_MANIFEST_MISMATCH");
            Map<String,Reference> refs = doc.references().stream().collect(Collectors.toMap(Reference::segmentId, r -> r));
            for (StoredSegment segment : manifest.segments()) {
                Reference ref = refs.get(segment.source().segmentId());
                if (!valid(segment, ref, doc)) throw new IndexConsistencyException("INDEX_SEGMENT_MISMATCH");
                output.add(row(segment, ref, 0, "segment"));
            }
        }
        return output;
    }
    private boolean matches(Manifest m, VisibleDocument d, String user, String kb) {
        return m != null && user.equals(d.ownerUserId()) && kb.equals(d.knowledgeBaseId()) && m.documentId().equals(d.documentId())
                && m.documentVersion() == d.documentVersion() && m.contentHash().equals(d.contentHash()) && m.configHash().equals(d.configHash()) && d.references() != null;
    }
    private boolean valid(StoredSegment segment, Reference ref, VisibleDocument doc) {
        return segment != null && ref != null && doc.documentId().equals(ref.documentId()) && doc.documentVersion() == ref.documentVersion()
                && doc.generationId().equals(ref.generationId()) && doc.contentHash().equals(ref.contentHash())
                && segment.source().referenceId().equals(ref.referenceId()) && segment.source().startOffset() == ref.startOffset()
                && segment.source().endOffset() == ref.endOffset() && hash(segment.content()).equals(ref.segmentHash());
    }
    private Map<String,Object> row(StoredSegment segment, Reference ref, float score, String source) {
        Map<String,Object> row = new LinkedHashMap<>(); row.put("id", ref.segmentId()); row.put("segmentId", ref.segmentId());
        row.put("content", segment.content()); row.put("similarity", score); row.put("source", source); row.put("reference", ref); return row;
    }
    private static String text(Object value) { return value instanceof String s ? s : ""; }
    private static float lexical(String query, String content) {
        if (query == null || content == null) return 0;
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        String[] words = content.toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length == 0 || terms.length == 0) return 0;
        float score = 0;
        // Preserve KnowledgeBase's existing simplified BM25 scoring while changing only eligibility.
        for (String term : terms) {
            if (term.isEmpty()) continue;
            int frequency = 0;
            for (String word : words) if (word.contains(term) || term.contains(word)) frequency++;
            if (frequency > 0) { float tf = (float) frequency / words.length; score += tf * 3.2f / (tf + 2.2f); }
        }
        return score / terms.length;
    }
}
