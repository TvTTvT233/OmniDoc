package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.acceptance.Qv04Fixture;
import com.javaee.aiservice.rag.vector.acceptance.Qv04IntegratedAcceptanceProvider;
import com.javaee.aiservice.rag.vector.acceptance.VectorBackendAcceptanceHarness;
import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;
import com.javaee.aiservice.rag.vector.local.LocalRedisHnswVectorBackend;
import com.javaee.aiservice.rag.vector.migration.FileMigrationCheckpointStore;
import com.javaee.aiservice.rag.vector.migration.MigrationOptions;
import com.javaee.aiservice.rag.vector.migration.MigrationReport;
import com.javaee.aiservice.rag.vector.migration.VectorMigrationException;
import com.javaee.aiservice.rag.vector.migration.VectorMigrationService;
import com.javaee.aiservice.rag.vector.routing.DefaultVectorBackendRouter;
import com.javaee.aiservice.rag.vector.routing.ShadowMetricsRecorder;
import com.javaee.aiservice.rag.vector.routing.ShadowRoutingOptions;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real QV01-QV03 production implementations backed by isolated Redis DB 15 and local Qdrant. */
public final class Qv04ProductionAcceptanceProvider implements Qv04IntegratedAcceptanceProvider {

    private static final int TEST_REDIS_DATABASE = 15;
    private static final Duration QDRANT_RESTART_TIMEOUT = Duration.ofSeconds(60);

    private final Qv04Fixture fixture = Qv04Fixture.load();
    private final Path repositoryRoot = findRepositoryRoot();
    private final String alias;
    private final String collectionPrefix;
    private final LettuceConnectionFactory redisConnectionFactory;
    private final RedisTemplate<String, Object> redisTemplate;
    private final LocalRedisHnswVectorBackend local;
    private final List<QdrantIntegrationSupport.Runtime> qdrantRuntimes = new ArrayList<>();
    private final QdrantIntegrationSupport.Runtime primary;
    private final QdrantIntegrationSupport.Runtime secondary;
    private final Path migrationDirectory;

    public Qv04ProductionAcceptanceProvider() {
        String testCollection = requireTestCollection(
                System.getProperty("qv04.test.collection", "qv04_test_local"));
        this.alias = testCollection + "_alias";
        this.collectionPrefix = testCollection + "_chunks_";
        this.redisConnectionFactory = redisConnectionFactory();
        this.redisTemplate = redisTemplate(redisConnectionFactory);
        cleanupLocalFixture();
        this.local = localBackend(redisTemplate);
        this.local.initialize();
        this.primary = openQdrant();
        this.secondary = openQdrant();
        try {
            this.migrationDirectory = Files.createTempDirectory("qv04-migration-");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create the QV04 migration checkpoint directory", exception);
        }
    }

    @Override
    public VectorBackend localBackend() {
        return local;
    }

    @Override
    public VectorBackend qdrantBackend() {
        return primary.backend();
    }

    @Override
    public VectorBackendAcceptanceHarness.VectorBackendLifecycle qdrantLifecycle() {
        return new VectorBackendAcceptanceHarness.VectorBackendLifecycle() {
            @Override
            public VectorBackend firstClient() {
                return primary.backend();
            }

            @Override
            public VectorBackend secondClient() {
                return secondary.backend();
            }

            @Override
            public VectorBackend restartAndGetClient() {
                restartQdrant();
                return openQdrantAfterRestart().backend();
            }
        };
    }

    @Override
    public ModeObservation observeMode(VectorStoreMode mode) {
        AtomicInteger qdrantResolutions = new AtomicInteger();
        VectorBackend baseline = mode == VectorStoreMode.QDRANT ? new TypeOnlyLocalBackend() : local;
        try (DefaultVectorBackendRouter router = new DefaultVectorBackendRouter(
                mode,
                baseline,
                () -> {
                    qdrantResolutions.incrementAndGet();
                    return primary.backend();
                },
                ShadowRoutingOptions.defaults(),
                observation -> {
                })) {
            router.health();
            return new ModeObservation(
                    router.mode(),
                    router.type(),
                    qdrantResolutions.get(),
                    mode == VectorStoreMode.QDRANT ? 0L : 1L);
        }
    }

    @Override
    public ShadowObservation runShadow(VectorQuery query, boolean injectQdrantFailure) {
        CapturingShadowRecorder recorder = new CapturingShadowRecorder();
        VectorBackend shadowQdrant = injectQdrantFailure
                ? new SearchFailingBackend(primary.backend())
                : primary.backend();
        List<VectorMatch> returned;
        try (DefaultVectorBackendRouter router = new DefaultVectorBackendRouter(
                VectorStoreMode.SHADOW,
                local,
                () -> shadowQdrant,
                new ShadowRoutingOptions(1.0d, 5_000L),
                recorder)) {
            returned = router.search(query);
            com.javaee.aiservice.rag.vector.routing.ShadowObservation observed = recorder.await();
            return new ShadowObservation(
                    observed.baselineIds(),
                    returned.stream().map(VectorMatch::id).toList(),
                    observed.qdrantIds(),
                    observed.overlapAtK(),
                    TimeUnit.NANOSECONDS.toMicros(observed.baselineDurationNanos()),
                    TimeUnit.NANOSECONDS.toMicros(observed.qdrantDurationNanos()),
                    observed.errorCode(),
                    1L,
                    false);
        }
    }

    @Override
    public MigrationObservation runMigration(Qv04Fixture acceptanceFixture) {
        local.upsertBatch(acceptanceFixture.records().stream()
                .map(Qv04Fixture.ExpandedRecord::record)
                .toList());
        Path checkpoint = migrationDirectory.resolve("checkpoint.json");
        FileMigrationCheckpointStore checkpointStore = new FileMigrationCheckpointStore(checkpoint);

        long beforeDryRun = primary.backend().count(acceptanceFixture.embedding());
        MigrationReport dryRun = new VectorMigrationService(
                local,
                primary.backend(),
                checkpointStore,
                primary.backend()).migrate(new MigrationOptions(
                        "qv04-dry-run",
                        true,
                        13,
                        acceptanceFixture.embedding()));
        long afterDryRun = primary.backend().count(acceptanceFixture.embedding());

        MigrationOptions liveOptions = new MigrationOptions(
                "qv04-live",
                false,
                13,
                acceptanceFixture.embedding());
        String checkpointBeforeFailure = checkpointText(checkpoint);
        try {
            new VectorMigrationService(
                    local,
                    new BatchFailingBackend(primary.backend()),
                    checkpointStore,
                    primary.backend()).migrate(liveOptions);
            throw new AssertionError("The injected migration batch failure was not observed");
        } catch (VectorMigrationException expected) {
            // The checkpoint must remain unchanged until a whole target batch succeeds.
        }
        String checkpointAfterFailure = checkpointText(checkpoint);

        VectorMigrationService resumedService = new VectorMigrationService(
                local,
                primary.backend(),
                checkpointStore,
                primary.backend());
        MigrationReport resumed = resumedService.migrate(liveOptions);
        String checkpointAfterResume = checkpointText(checkpoint);
        MigrationReport rerun = resumedService.migrate(liveOptions);

        VectorRecord sourceProbe = acceptanceFixture.records().get(0).record();
        boolean sourceDeleted = local.search(new VectorQuery(
                        sourceProbe.vector(),
                        5,
                        com.javaee.aiservice.rag.vector.contract.VectorFilter.forKnowledgeBase(
                                sourceProbe.scope().userId(),
                                sourceProbe.scope().knowledgeBaseId()),
                        sourceProbe.embedding())).stream()
                .noneMatch(match -> match.id().equals(sourceProbe.id()));

        return new MigrationObservation(
                afterDryRun - beforeDryRun,
                resumed.sourceRecords(),
                resumed.successfulRecords(),
                resumed.failedRecords(),
                resumed.qdrantRecords(),
                checkpointBeforeFailure,
                checkpointAfterFailure,
                checkpointAfterResume,
                rerun.qdrantRecords(),
                dryRun.assumedEmbedding().equals(acceptanceFixture.embedding())
                        && resumed.assumedEmbedding().equals(acceptanceFixture.embedding()),
                sourceDeleted);
    }

    @Override
    public void close() {
        cleanupLocalFixture();
        QdrantIntegrationSupport.Runtime cleanupRuntime = qdrantRuntimes.isEmpty()
                ? null
                : qdrantRuntimes.get(qdrantRuntimes.size() - 1);
        if (cleanupRuntime != null) {
            QdrantIntegrationSupport.cleanup(
                    cleanupRuntime.client(),
                    alias,
                    cleanupRuntime.manager().collection());
        }
        for (QdrantIntegrationSupport.Runtime runtime : qdrantRuntimes) {
            runtime.close();
        }
        redisConnectionFactory.destroy();
        try {
            Files.deleteIfExists(migrationDirectory.resolve("checkpoint.json.tmp"));
            Files.deleteIfExists(migrationDirectory.resolve("checkpoint.json"));
            Files.deleteIfExists(migrationDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to clean the QV04 checkpoint fixture", exception);
        }
    }

    private QdrantIntegrationSupport.Runtime openQdrant() {
        QdrantIntegrationSupport.Runtime runtime = QdrantIntegrationSupport.open(
                alias,
                collectionPrefix,
                fixture.embedding());
        qdrantRuntimes.add(runtime);
        return runtime;
    }

    private QdrantIntegrationSupport.Runtime openQdrantAfterRestart() {
        long deadline = System.nanoTime() + QDRANT_RESTART_TIMEOUT.toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return openQdrant();
            } catch (RuntimeException failure) {
                lastFailure = failure;
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Qdrant restart", interrupted);
                }
            }
        }
        throw new IllegalStateException("Qdrant did not recover after restart", lastFailure);
    }

    private void restartQdrant() {
        Path compose = repositoryRoot.resolve("docker-compose.local.yml");
        ProcessBuilder processBuilder = new ProcessBuilder(
                "docker", "compose", "-f", compose.toString(), "restart", "qdrant");
        processBuilder.directory(repositoryRoot.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String ignoredOutput = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IllegalStateException("Controlled Qdrant restart failed");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to execute the controlled Qdrant restart", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during the controlled Qdrant restart", exception);
        }
    }

    private LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                System.getProperty("qv04.redis.host", "localhost"),
                Integer.getInteger("qv04.redis.port", 6379));
        configuration.setDatabase(TEST_REDIS_DATABASE);
        String password = environmentValue("REDIS_PASSWORD");
        if (!password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        factory.getConnection().ping();
        return factory;
    }

    private static RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();
        template.setConnectionFactory(factory);
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    private LocalRedisHnswVectorBackend localBackend(RedisTemplate<String, Object> template) {
        LocalRedisHnswVectorBackend backend = new LocalRedisHnswVectorBackend(template);
        ReflectionTestUtils.setField(backend, "defaultEmbeddingModel", fixture.embedding().model());
        ReflectionTestUtils.setField(backend, "defaultSchemaVersion", fixture.embedding().schemaVersion());
        ReflectionTestUtils.setField(backend, "hnswM", 16);
        ReflectionTestUtils.setField(backend, "hnswEfConstruction", 100);
        ReflectionTestUtils.setField(backend, "hnswEf", 64);
        ReflectionTestUtils.setField(backend, "hnswMaxItems", 10_000);
        return backend;
    }

    private void cleanupLocalFixture() {
        deleteKeys(redisTemplate.keys("vector:qv04-*"));
        deleteKeys(redisTemplate.keys("metadata:qv04-*"));
    }

    private void deleteKeys(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private String environmentValue(String name) {
        String processValue = System.getenv(name);
        if (processValue != null && !processValue.isBlank()) {
            return processValue;
        }
        Path dotEnv = repositoryRoot.resolve(".env");
        if (!Files.isRegularFile(dotEnv)) {
            return "";
        }
        try {
            return Files.readAllLines(dotEnv).stream()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("#") && line.startsWith(name + "="))
                    .map(line -> line.substring(name.length() + 1).trim())
                    .map(Qv04ProductionAcceptanceProvider::unquote)
                    .findFirst()
                    .orElse("");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read local acceptance configuration", exception);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String checkpointText(Path checkpoint) {
        try {
            return Files.exists(checkpoint) ? Files.readString(checkpoint) : "";
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect the migration checkpoint", exception);
        }
    }

    private static String requireTestCollection(String collection) {
        String normalized = collection == null ? "" : collection.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("qv04_test_")) {
            throw new IllegalArgumentException("QV04 Collection must start with qv04_test_");
        }
        return normalized;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("docker-compose.local.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate docker-compose.local.yml");
    }

    private static final class CapturingShadowRecorder implements ShadowMetricsRecorder {

        private final AtomicReference<com.javaee.aiservice.rag.vector.routing.ShadowObservation> observation =
                new AtomicReference<>();
        private final CountDownLatch recorded = new CountDownLatch(1);

        @Override
        public void record(com.javaee.aiservice.rag.vector.routing.ShadowObservation value) {
            observation.set(value);
            recorded.countDown();
        }

        private com.javaee.aiservice.rag.vector.routing.ShadowObservation await() {
            try {
                if (!recorded.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Shadow observation timed out");
                }
                return observation.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Shadow observation", exception);
            }
        }
    }

    private abstract static class DelegatingBackend implements VectorBackend {

        protected final VectorBackend delegate;

        private DelegatingBackend(VectorBackend delegate) {
            this.delegate = delegate;
        }

        @Override
        public VectorBackendType type() {
            return delegate.type();
        }

        @Override
        public void upsert(VectorRecord record) {
            delegate.upsert(record);
        }

        @Override
        public void upsertBatch(List<VectorRecord> records) {
            delegate.upsertBatch(records);
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            return delegate.search(query);
        }

        @Override
        public void deleteById(String id, VectorScope scope) {
            delegate.deleteById(id, scope);
        }

        @Override
        public long deleteByDocument(String documentId, VectorScope scope) {
            return delegate.deleteByDocument(documentId, scope);
        }

        @Override
        public VectorBackendHealth health() {
            return delegate.health();
        }
    }

    private static final class SearchFailingBackend extends DelegatingBackend {

        private SearchFailingBackend(VectorBackend delegate) {
            super(delegate);
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            throw new VectorBackendException(
                    VectorBackendType.QDRANT,
                    VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE,
                    "Injected QV04 Qdrant search failure",
                    true);
        }
    }

    private static final class BatchFailingBackend extends DelegatingBackend {

        private BatchFailingBackend(VectorBackend delegate) {
            super(delegate);
        }

        @Override
        public void upsertBatch(List<VectorRecord> records) {
            throw new VectorBackendException(
                    VectorBackendType.QDRANT,
                    VectorErrorCode.VECTOR_UPSERT_FAILED,
                    "Injected QV04 migration batch failure",
                    true);
        }
    }

    private static final class TypeOnlyLocalBackend implements VectorBackend {

        @Override
        public VectorBackendType type() {
            return VectorBackendType.LOCAL_REDIS_HNSW;
        }

        @Override
        public void upsert(VectorRecord record) {
            throw disabled();
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            throw disabled();
        }

        @Override
        public void deleteById(String id, VectorScope scope) {
            throw disabled();
        }

        @Override
        public long deleteByDocument(String documentId, VectorScope scope) {
            throw disabled();
        }

        @Override
        public VectorBackendHealth health() {
            throw disabled();
        }

        private static IllegalStateException disabled() {
            return new IllegalStateException("Local backend must not be called in qdrant mode");
        }
    }
}
