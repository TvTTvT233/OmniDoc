package com.javaee.aiservice.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.rag.VectorStore;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.javaee.aiservice.consistency.IndexContracts.*;

/** Immutable build manifests are written before any vectors, so partial builds remain reclaimable. */
@Component
public class GenerationStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final VectorStore vectors;
    private int cleanupCursor;
    public GenerationStore(StringRedisTemplate redis, ObjectMapper mapper, VectorStore vectors) {
        this.redis = redis; this.mapper = mapper; this.vectors = vectors;
    }
    public void create(Manifest manifest) {
        try {
            assertWritable(manifest.generationId());
            String json = mapper.writeValueAsString(manifest);
            Boolean created = redis.opsForValue().setIfAbsent(key(manifest.generationId()), json);
            if (!Boolean.TRUE.equals(created)) throw new IndexConsistencyException("GENERATION_ALREADY_EXISTS");
            redis.opsForSet().add(scopeKey(manifest.ownerUserId(), manifest.knowledgeBaseId()), manifest.generationId());
            // A cleanup can retire an attempt even before its manifest exists.
            try { assertWritable(manifest.generationId()); }
            catch (IndexConsistencyException retired) {
                cleanup(manifest.generationId(), manifest.documentId(), manifest.ownerUserId(), manifest.knowledgeBaseId());
                throw retired;
            }
        } catch (IndexConsistencyException e) { throw e; }
        catch (Exception e) { throw new IndexConsistencyException("INDEX_STORAGE_UNAVAILABLE"); }
    }
    public Manifest read(String generationId) {
        try {
            String value = redis.opsForValue().get(key(generationId));
            return value == null ? null : mapper.readValue(value, Manifest.class);
        } catch (Exception e) { throw new IndexConsistencyException("INDEX_STORAGE_UNAVAILABLE"); }
    }
    public List<Manifest> candidates(String userId, String knowledgeBaseId) {
        try {
            Set<String> ids = redis.opsForSet().members(scopeKey(userId, knowledgeBaseId));
            if (ids == null || ids.isEmpty()) return List.of();
            if (ids.size() > 10000) throw new IndexConsistencyException("INDEX_SCOPE_TOO_LARGE");
            List<Manifest> result = new ArrayList<>();
            for (String id : ids) {
                Manifest manifest = read(id);
                if (manifest != null && userId.equals(manifest.ownerUserId()) && knowledgeBaseId.equals(manifest.knowledgeBaseId())) result.add(manifest);
            }
            return result;
        } catch (IndexConsistencyException e) { throw e; }
        catch (Exception e) { throw new IndexConsistencyException("INDEX_STORAGE_UNAVAILABLE"); }
    }
    public void cleanup(String generationId, String documentId, String owner, String knowledgeBaseId) {
        Manifest manifest = read(generationId);
        if (manifest == null) {
            retire(new Tombstone(generationId, documentId, owner, knowledgeBaseId, List.of()));
            // Re-read after retirement to cover a racing create that passed its first guard.
            manifest = read(generationId);
            if (manifest == null) { reapOne(generationId); return; }
        }
        if (!documentId.equals(manifest.documentId()) || !owner.equals(manifest.ownerUserId()) || !knowledgeBaseId.equals(manifest.knowledgeBaseId()))
            throw new IndexConsistencyException("INVALID_CLEANUP_SCOPE");
        try {
            // Retain only the scoped IDs indefinitely. A late in-flight vector write is reclaimed after restart too.
            Tombstone tombstone = new Tombstone(generationId, documentId, owner, knowledgeBaseId,
                    manifest.segments().stream().map(s -> s.source().segmentId()).toList());
            retire(tombstone);
            reapOne(generationId);
            redis.delete(key(generationId));
            redis.opsForSet().remove(scopeKey(owner, knowledgeBaseId), generationId);
        } catch (IndexConsistencyException e) { throw e; }
        catch (Exception e) { throw new IndexConsistencyException("INDEX_STORAGE_UNAVAILABLE"); }
    }
    public void assertWritable(String generation) {
        if (Boolean.TRUE.equals(redis.hasKey(tombstoneKey(generation)))) throw new IndexConsistencyException("GENERATION_RETIRED");
    }
    public void reclaimRetired() {
        Set<String> ids = redis.opsForSet().members("index:v1:tombstones");
        if (ids == null || ids.isEmpty()) return;
        List<String> ordered = ids.stream().sorted().toList();
        for (int i = 0; i < Math.min(100, ordered.size()); i++) {
            String id = ordered.get(Math.floorMod(cleanupCursor++, ordered.size()));
            try { reapOne(id); } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Retired generation cleanup pending: {}", id);
            }
        }
    }
    private void retire(Tombstone tombstone) {
        try {
            String serialized = mapper.writeValueAsString(tombstone);
            if (tombstone.segmentIds().isEmpty()) redis.opsForValue().setIfAbsent(tombstoneKey(tombstone.generationId()), serialized);
            else redis.opsForValue().set(tombstoneKey(tombstone.generationId()), serialized);
            redis.opsForSet().add("index:v1:tombstones", tombstone.generationId());
        } catch (Exception e) { throw new IndexConsistencyException("INDEX_STORAGE_UNAVAILABLE"); }
    }
    private record Tombstone(String generationId, String documentId, String owner, String knowledgeBaseId, List<String> segmentIds) {}
    private void reapOne(String generation) {
        try {
            String raw = redis.opsForValue().get(tombstoneKey(generation));
            if (raw == null) return;
            Tombstone tombstone = mapper.readValue(raw, Tombstone.class);
            Manifest lateManifest = read(generation);
            if (lateManifest != null) {
                if (!tombstone.documentId().equals(lateManifest.documentId()) || !tombstone.owner().equals(lateManifest.ownerUserId())
                        || !tombstone.knowledgeBaseId().equals(lateManifest.knowledgeBaseId()))
                    throw new IndexConsistencyException("INVALID_CLEANUP_SCOPE");
                Set<String> allIds = new LinkedHashSet<>(tombstone.segmentIds());
                lateManifest.segments().forEach(segment -> allIds.add(segment.source().segmentId()));
                tombstone = new Tombstone(generation, tombstone.documentId(), tombstone.owner(), tombstone.knowledgeBaseId(), List.copyOf(allIds));
                // A process may have died between late manifest creation and its second retirement guard.
                // Make that manifest's IDs durable before releasing its body or deleting any vectors.
                retire(tombstone);
            }
            for (String id : tombstone.segmentIds()) vectors.deleteById(id, new VectorScope(tombstone.owner(), tombstone.knowledgeBaseId()));
            if (lateManifest != null) {
                redis.delete(key(generation));
                redis.opsForSet().remove(scopeKey(tombstone.owner(), tombstone.knowledgeBaseId()), generation);
            }
        } catch (Exception e) { throw new IndexConsistencyException("INDEX_CLEANUP_UNAVAILABLE"); }
    }
    private String tombstoneKey(String id) { return key(id) + ":retired"; }
    private String key(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IndexConsistencyException("INVALID_GENERATION_ID");
        return "index:v1:generation:" + id;
    }
    private String scopeKey(String owner, String kb) { return "index:v1:scope:" + hash(owner + "\n" + kb); }
}
