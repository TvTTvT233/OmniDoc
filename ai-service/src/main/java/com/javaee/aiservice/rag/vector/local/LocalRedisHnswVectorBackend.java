package com.javaee.aiservice.rag.vector.local;

import com.github.jelmerk.knn.DistanceFunctions;
import com.github.jelmerk.knn.Item;
import com.github.jelmerk.knn.SearchResult;
import com.github.jelmerk.knn.hnsw.HnswIndex;
import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorExportRequest;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorRecordExporter;
import com.javaee.aiservice.rag.vector.contract.VectorRecordPage;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Existing Redis persistence and in-process HNSW index exposed through the frozen backend contract. */
@Component
@Conditional(LocalVectorEnabledCondition.class)
public class LocalRedisHnswVectorBackend
        implements VectorBackend, LegacyVectorOperations, VectorRecordExporter {

    private static final Logger log = LoggerFactory.getLogger(LocalRedisHnswVectorBackend.class);
    private static final String VECTOR_PREFIX = "vector:";
    private static final String METADATA_PREFIX = "metadata:";
    private static final Set<String> RESERVED_METADATA_KEYS = Set.of(
            "id", "userId", "knowledgeBaseId", "documentId",
            "embeddingModel", "embeddingDimension", "schemaVersion");

    private final RedisTemplate<String, Object> redisTemplate;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong versionSequence = new AtomicLong();

    @Value("${ai.vector.embedding.model:text-embedding-v1}")
    private String defaultEmbeddingModel;

    @Value("${ai.vector.embedding.schema-version:1}")
    private int defaultSchemaVersion;

    @Value("${ai.vector.hnsw.m:16}")
    private int hnswM;

    @Value("${ai.vector.hnsw.ef-construction:200}")
    private int hnswEfConstruction;

    @Value("${ai.vector.hnsw.ef:128}")
    private int hnswEf;

    @Value("${ai.vector.hnsw.max-items:200000}")
    private int hnswMaxItems;

    private volatile HnswIndex<String, float[], FloatArrayItem, Float> index;
    private volatile EmbeddingSpec activeEmbedding;

    public LocalRedisHnswVectorBackend(RedisTemplate<String, Object> redisTemplate) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate must not be null");
        }
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void initialize() {
        lock.writeLock().lock();
        try {
            rebuildIndexFromRedis();
        } catch (Exception e) {
            index = null;
            activeEmbedding = null;
            log.warn("HNSW 索引初始化失败，将在首次写入时构建: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public VectorBackendType type() {
        return VectorBackendType.LOCAL_REDIS_HNSW;
    }

    @Override
    public void upsert(VectorRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        lock.writeLock().lock();
        try {
            ensureCompatibleIndex(record.embedding());
            persist(record);
            replaceIndexItem(record.id(), record.vector());
            log.info("向量存储成功: id={}, dimension={}", record.id(), record.embedding().dimension());
        } catch (VectorBackendException e) {
            throw e;
        } catch (Exception e) {
            if (index != null && index.size() == 0) {
                index = null;
                activeEmbedding = null;
            }
            throw backendException(VectorErrorCode.VECTOR_UPSERT_FAILED, "Local vector upsert failed", true, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<VectorMatch> search(VectorQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        lock.readLock().lock();
        try {
            ensureCompatibleQuery(query.embedding());
            return searchInternal(query.vector(), query.topK(), metadata -> matchesFilter(metadata, query.filter()));
        } catch (VectorBackendException e) {
            throw e;
        } catch (Exception e) {
            throw backendException(VectorErrorCode.VECTOR_SEARCH_FAILED, "Local vector search failed", true, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<VectorMatch> searchLegacy(float[] vector, int topK, Map<String, Object> filters,
                                          EmbeddingSpec embedding) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        if (topK <= 0 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
        if (embedding == null) {
            throw new IllegalArgumentException("embedding must not be null");
        }
        Map<String, Object> safeFilters = filters == null ? Map.of() : new LinkedHashMap<>(filters);
        lock.readLock().lock();
        try {
            ensureCompatibleQuery(embedding);
            return searchInternal(vector, topK, metadata -> matchesAttributes(metadata, safeFilters));
        } catch (VectorBackendException e) {
            throw e;
        } catch (Exception e) {
            throw backendException(VectorErrorCode.VECTOR_SEARCH_FAILED, "Local legacy vector search failed", true, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void deleteById(String id, VectorScope scope) {
        requireText(id, "id");
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        lock.writeLock().lock();
        try {
            Map<String, Object> metadata = getMetadata(id);
            if (!matchesScope(metadata, scope)) {
                return;
            }
            deleteInternal(id);
        } catch (VectorBackendException e) {
            throw e;
        } catch (Exception e) {
            throw backendException(VectorErrorCode.VECTOR_DELETE_FAILED, "Local vector delete failed", true, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void deleteLegacy(String id) {
        requireText(id, "id");
        lock.writeLock().lock();
        try {
            deleteInternal(id);
        } catch (VectorBackendException e) {
            throw e;
        } catch (Exception e) {
            throw backendException(VectorErrorCode.VECTOR_DELETE_FAILED, "Local legacy vector delete failed", true, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public long deleteByDocument(String documentId, VectorScope scope) {
        requireText(documentId, "documentId");
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        lock.writeLock().lock();
        try {
            List<String> ids = activeIds();
            long deleted = 0;
            for (String id : ids) {
                Map<String, Object> metadata = getMetadata(id);
                if (matchesScope(metadata, scope)
                        && documentId.equals(text(metadata.get("documentId")))) {
                    deleteInternal(id);
                    deleted++;
                }
            }
            return deleted;
        } catch (VectorBackendException e) {
            throw e;
        } catch (Exception e) {
            throw backendException(VectorErrorCode.VECTOR_DELETE_FAILED,
                    "Local document vector delete failed", true, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public VectorBackendHealth health() {
        lock.readLock().lock();
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("indexedItems", index == null ? 0 : index.size());
            if (activeEmbedding != null) {
                details.put("embeddingModel", activeEmbedding.model());
                details.put("embeddingDimension", activeEmbedding.dimension());
                details.put("schemaVersion", activeEmbedding.schemaVersion());
            }
            return new VectorBackendHealth(type(), true, "local backend ready", details);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public VectorRecordPage exportPage(VectorExportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        lock.readLock().lock();
        try {
            List<String> ids = scanKeys(VECTOR_PREFIX + "*", 1000).stream()
                    .map(key -> key.substring(VECTOR_PREFIX.length()))
                    .filter(id -> request.cursor().isBlank() || id.compareTo(request.cursor()) > 0)
                    .sorted()
                    .toList();
            int pageSize = Math.min(request.batchSize(), ids.size());
            List<VectorRecord> records = new ArrayList<>(pageSize);
            for (int index = 0; index < pageSize; index++) {
                records.add(exportRecord(ids.get(index), request.assumedEmbedding()));
            }
            boolean complete = pageSize == ids.size();
            String nextCursor = complete || records.isEmpty() ? "" : records.get(records.size() - 1).id();
            return new VectorRecordPage(records, nextCursor, complete);
        } catch (VectorBackendException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw backendException(
                    VectorErrorCode.VECTOR_MIGRATION_FAILED,
                    "Local vector export failed",
                    true,
                    exception);
        } finally {
            lock.readLock().unlock();
        }
    }

    private VectorRecord exportRecord(String id, EmbeddingSpec assumedEmbedding) {
        float[] vector = convertToFloatArray(redisTemplate.opsForValue().get(VECTOR_PREFIX + id));
        if (vector == null || vector.length == 0) {
            throw backendException(
                    VectorErrorCode.VECTOR_MIGRATION_FAILED,
                    "Local vector export found an empty vector",
                    false,
                    null);
        }
        Map<String, Object> stored = new LinkedHashMap<>(getMetadata(id));
        EmbeddingSpec embedding = exportEmbedding(stored, vector.length, assumedEmbedding);
        if (!embedding.equals(assumedEmbedding)) {
            throw backendException(
                    VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                    "Local vector export found an incompatible embedding specification",
                    false,
                    null);
        }
        String userId = text(stored.get("userId"));
        String knowledgeBaseId = text(stored.get("knowledgeBaseId"));
        String documentId = text(stored.get("documentId"));
        VectorScope scope = new VectorScope(
                userId == null ? "system" : userId,
                knowledgeBaseId == null ? "default" : knowledgeBaseId);
        stored.keySet().removeAll(RESERVED_METADATA_KEYS);
        return new VectorRecord(
                id,
                documentId == null ? id : documentId,
                vector,
                scope,
                embedding,
                stored);
    }

    private static EmbeddingSpec exportEmbedding(
            Map<String, Object> metadata,
            int vectorDimension,
            EmbeddingSpec assumedEmbedding
    ) {
        String storedModel = text(metadata.get("embeddingModel"));
        String model = storedModel == null ? assumedEmbedding.model() : storedModel;
        int dimension = intValue(metadata.get("embeddingDimension"), vectorDimension);
        int schemaVersion = intValue(metadata.get("schemaVersion"), assumedEmbedding.schemaVersion());
        return new EmbeddingSpec(model, dimension, schemaVersion);
    }

    private void persist(VectorRecord record) {
        String vectorKey = VECTOR_PREFIX + record.id();
        String metadataKey = METADATA_PREFIX + record.id();

        float[] vector = record.vector();
        List<Float> vectorList = new ArrayList<>(vector.length);
        for (float value : vector) {
            vectorList.add(value);
        }

        Map<String, Object> storedMetadata = new LinkedHashMap<>(record.metadata());
        storedMetadata.put("userId", record.scope().userId());
        storedMetadata.put("knowledgeBaseId", record.scope().knowledgeBaseId());
        storedMetadata.put("documentId", record.documentId());
        storedMetadata.put("embeddingModel", record.embedding().model());
        storedMetadata.put("embeddingDimension", record.embedding().dimension());
        storedMetadata.put("schemaVersion", record.embedding().schemaVersion());

        redisTemplate.opsForValue().set(vectorKey, vectorList);
        redisTemplate.delete(metadataKey);
        redisTemplate.opsForHash().putAll(metadataKey, storedMetadata);
    }

    private List<VectorMatch> searchInternal(float[] vector, int topK, MetadataPredicate predicate) {
        if (index == null || index.size() == 0) {
            return List.of();
        }
        int candidateCount = index.size();
        List<SearchResult<FloatArrayItem, Float>> hits = index.findNearest(vector, candidateCount);
        List<VectorMatch> matches = new ArrayList<>(Math.min(topK, hits.size()));
        for (SearchResult<FloatArrayItem, Float> hit : hits) {
            if (matches.size() >= topK) {
                break;
            }
            String id = hit.item().id();
            Map<String, Object> metadata = getMetadata(id);
            if (metadata.isEmpty() || !predicate.test(metadata)) {
                continue;
            }
            String documentId = text(metadata.get("documentId"));
            if (documentId == null) {
                documentId = id;
            }
            matches.add(new VectorMatch(id, documentId, 1.0f - hit.distance(), metadata));
        }
        return matches;
    }

    private void replaceIndexItem(String id, float[] vector) {
        FloatArrayItem item = new FloatArrayItem(id, vector, versionSequence.incrementAndGet());
        try {
            if (!index.add(item)) {
                throw new IllegalStateException("HNSW rejected replacement for id=" + id);
            }
        } catch (Exception firstFailure) {
            log.info("HNSW 索引需要重建以回收历史版本容量: {}", firstFailure.getMessage());
            rebuildIndexFromRedis();
            if (index == null || index.get(id).isEmpty()) {
                throw firstFailure;
            }
        }
    }

    private void deleteInternal(String id) {
        redisTemplate.delete(VECTOR_PREFIX + id);
        redisTemplate.delete(METADATA_PREFIX + id);
        if (index == null) {
            return;
        }
        long version = versionSequence.incrementAndGet();
        boolean removed = index.remove(id, version);
        if (!removed && index.get(id).isPresent()) {
            rebuildIndexFromRedis();
        }
        if (index != null && index.size() == 0) {
            index = null;
            activeEmbedding = null;
        }
    }

    private void ensureCompatibleIndex(EmbeddingSpec embedding) {
        if (index == null) {
            activeEmbedding = embedding;
            index = newIndex(embedding.dimension());
            return;
        }
        ensureCompatibleEmbedding(embedding);
    }

    private void ensureCompatibleQuery(EmbeddingSpec embedding) {
        if (index == null || activeEmbedding == null) {
            return;
        }
        ensureCompatibleEmbedding(embedding);
    }

    private void ensureCompatibleEmbedding(EmbeddingSpec embedding) {
        if (activeEmbedding.dimension() != embedding.dimension()) {
            throw backendException(VectorErrorCode.VECTOR_DIMENSION_MISMATCH,
                    "Vector dimension does not match local index", false, null);
        }
        if (!activeEmbedding.model().equals(embedding.model())
                || activeEmbedding.schemaVersion() != embedding.schemaVersion()) {
            throw backendException(VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                    "Embedding specification does not match local index", false, null);
        }
    }

    private HnswIndex<String, float[], FloatArrayItem, Float> newIndex(int dimension) {
        HnswIndex<String, float[], FloatArrayItem, Float> created = HnswIndex
                .<float[], Float>newBuilder(dimension, DistanceFunctions.FLOAT_COSINE_DISTANCE, hnswMaxItems)
                .withM(hnswM)
                .withEfConstruction(hnswEfConstruction)
                .withEf(hnswEf)
                .withRemoveEnabled()
                .build();
        log.info("HNSW 索引已创建: dimension={}, M={}, efConstruction={}, ef={}, maxItems={}",
                dimension, hnswM, hnswEfConstruction, hnswEf, hnswMaxItems);
        return created;
    }

    private void rebuildIndexFromRedis() {
        List<String> keys = scanKeys(VECTOR_PREFIX + "*", 1000);
        if (keys.isEmpty()) {
            index = null;
            activeEmbedding = null;
            log.info("未发现已有向量数据，HNSW 索引延迟构建");
            return;
        }

        HnswIndex<String, float[], FloatArrayItem, Float> rebuilt = null;
        EmbeddingSpec rebuiltEmbedding = null;
        int loaded = 0;
        for (String key : keys) {
            String id = key.substring(VECTOR_PREFIX.length());
            float[] vector = convertToFloatArray(redisTemplate.opsForValue().get(key));
            if (vector == null || vector.length == 0) {
                continue;
            }
            Map<String, Object> metadata = getMetadata(id);
            EmbeddingSpec embedding = storedEmbedding(metadata, vector.length);
            if (rebuilt == null) {
                rebuilt = newIndex(embedding.dimension());
                rebuiltEmbedding = embedding;
            }
            if (!rebuiltEmbedding.equals(embedding)) {
                log.warn("HNSW 索引重建时跳过不兼容向量 id={}", id);
                continue;
            }
            try {
                if (rebuilt.add(new FloatArrayItem(id, vector, versionSequence.incrementAndGet()))) {
                    loaded++;
                }
            } catch (Exception e) {
                log.warn("HNSW 索引重建时跳过向量 id={}: {}", id, e.getMessage());
            }
        }
        index = rebuilt;
        activeEmbedding = rebuiltEmbedding;
        log.info("HNSW 索引重建完成，载入 {} 个向量", loaded);
    }

    private EmbeddingSpec storedEmbedding(Map<String, Object> metadata, int vectorDimension) {
        String model = text(metadata.get("embeddingModel"));
        if (model == null) {
            model = defaultEmbeddingModel;
        }
        int dimension = intValue(metadata.get("embeddingDimension"), vectorDimension);
        int schemaVersion = intValue(metadata.get("schemaVersion"), defaultSchemaVersion);
        return new EmbeddingSpec(model, dimension, schemaVersion);
    }

    private List<String> activeIds() {
        if (index == null) {
            return List.of();
        }
        Collection<FloatArrayItem> items = index.items();
        List<String> ids = new ArrayList<>(items.size());
        for (FloatArrayItem item : items) {
            ids.add(item.id());
        }
        return ids;
    }

    private Map<String, Object> getMetadata(String id) {
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(METADATA_PREFIX + id);
        if (hash == null || hash.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : hash.entrySet()) {
            metadata.put(entry.getKey().toString(), entry.getValue());
        }
        return metadata;
    }

    private List<String> scanKeys(String pattern, int count) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        try (var cursor = redisTemplate.getConnectionFactory().getConnection().scan(options)) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        return keys;
    }

    private boolean matchesFilter(Map<String, Object> metadata, VectorFilter filter) {
        return matchesScope(metadata, filter.scope())
                && (filter.documentId() == null
                    || filter.documentId().equals(text(metadata.get("documentId"))))
                && matchesAttributes(metadata, filter.attributes());
    }

    private boolean matchesScope(Map<String, Object> metadata, VectorScope scope) {
        return scope.userId().equals(text(metadata.get("userId")))
                && scope.knowledgeBaseId().equals(text(metadata.get("knowledgeBaseId")));
    }

    private boolean matchesAttributes(Map<String, Object> metadata, Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            Object expected = attribute.getValue();
            if (expected == null || expected.toString().isBlank()) {
                continue;
            }
            Object actual = metadata.get(attribute.getKey());
            if (actual == null || !expected.toString().equals(actual.toString())) {
                return false;
            }
        }
        return true;
    }

    private float[] convertToFloatArray(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof float[] vector) {
            return vector.clone();
        }
        if (value instanceof List<?> values) {
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                Object element = values.get(i);
                if (!(element instanceof Number number)) {
                    throw new IllegalArgumentException("Stored vector contains a non-numeric element");
                }
                vector[i] = number.floatValue();
            }
            return vector;
        }
        throw new IllegalArgumentException("Unsupported stored vector type: " + value.getClass().getName());
    }

    private VectorBackendException backendException(VectorErrorCode code, String message,
                                                    boolean retryable, Throwable cause) {
        return new VectorBackendException(type(), code, message, retryable, cause);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static int intValue(Object value, int fallback) {
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    @FunctionalInterface
    private interface MetadataPredicate {
        boolean test(Map<String, Object> metadata);
    }

    /** HNSW item with a monotonic in-process version so replacements and deletes cannot go stale. */
    private static final class FloatArrayItem implements Item<String, float[]>, Serializable {
        private static final long serialVersionUID = 1L;
        private final String id;
        private final float[] vector;
        private final long version;

        private FloatArrayItem(String id, float[] vector, long version) {
            this.id = Objects.requireNonNull(id, "id");
            this.vector = vector.clone();
            this.version = version;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public float[] vector() {
            return vector.clone();
        }

        @Override
        public int dimensions() {
            return vector.length;
        }

        @Override
        public long version() {
            return version;
        }
    }
}
