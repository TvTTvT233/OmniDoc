package com.javaee.fileservice.controller;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.common.utils.JwtUtils;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.config.OwnedFileDownloadProperties;
import com.javaee.fileservice.config.SecurityConfig;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.exception.OwnedFileUnavailableException;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.security.AuthenticatedFileOwner;
import com.javaee.fileservice.service.OwnedFileObjectStore;
import com.javaee.fileservice.service.impl.OwnedFileDownloadStaging;
import com.javaee.fileservice.service.impl.OwnerAwareFileServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerAwareFilePrecommitIntegrityHttpTest {
    private static final String FILE_ID = "file-http-integrity";
    private static final String OWNER_ID = "42";
    private static final byte[] NORMAL_BYTES = "%PDF-1.7\nprecommit-integrity"
            .getBytes(StandardCharsets.UTF_8);

    private Path temporaryDirectory;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private ConfigurableApplicationContext context;
    private TestState state;

    @BeforeAll
    void startServer() throws IOException {
        temporaryDirectory = Files.createTempDirectory("owned-file-http-");
        startContext();
    }

    @AfterAll
    void stopServer() throws IOException {
        closeContext();
        deleteTree(temporaryDirectory);
    }

    @BeforeEach
    void resetState() {
        state = context.getBean(TestState.class);
        state.reset();
    }

    @AfterEach
    void assertNoStagedPayloadRemains() throws Exception {
        awaitPayloadCount(0);
    }

    @Test
    void normalDownloadCommitsVerifiedHeadersAndExactBytes() throws Exception {
        HttpResponse<byte[]> response = get(OWNER_ID, FILE_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(NORMAL_BYTES);
        assertThat(response.headers().firstValueAsLong("Content-Length"))
                .hasValue(NORMAL_BYTES.length);
        assertThat(response.headers().firstValue("X-Content-SHA256"))
                .hasValue(sha256(NORMAL_BYTES));
        assertThat(response.headers().firstValue("ETag"))
                .hasValue('"' + sha256(NORMAL_BYTES) + '"');
        assertThat(response.headers().firstValue("Digest")).isPresent();
    }

    @Test
    void databaseDigestMismatchFailsBeforeAny200OrIntegrityHeaders() throws Exception {
        state.metadata = metadata(OWNER_ID, NORMAL_BYTES.length, "f".repeat(64));

        assertPrecommitUnavailable(get(OWNER_ID, FILE_ID));
    }

    @Test
    void equalLengthObjectTamperingFailsBeforeAny200OrCompleteBytes() throws Exception {
        byte[] changed = NORMAL_BYTES.clone();
        changed[changed.length - 1] ^= 1;
        state.objectBytes = changed;

        assertPrecommitUnavailable(get(OWNER_ID, FILE_ID));
    }

    @Test
    void shortAndLongReadsBothFailBeforeAny200() throws Exception {
        state.objectBytes = java.util.Arrays.copyOf(NORMAL_BYTES, NORMAL_BYTES.length - 1);
        assertPrecommitUnavailable(get(OWNER_ID, FILE_ID));

        state.objectBytes = (new String(NORMAL_BYTES, StandardCharsets.UTF_8) + "x")
                .getBytes(StandardCharsets.UTF_8);
        assertPrecommitUnavailable(get(OWNER_ID, FILE_ID));
    }

    @Test
    void storageReadFailureMapsToFrozen503BeforeAny200() throws Exception {
        state.failDuringRead = true;

        assertPrecommitUnavailable(get(OWNER_ID, FILE_ID));
    }

    @Test
    void storageOpenFailureAndConfiguredHardLimitMapTo503BeforeAny200() throws Exception {
        state.failOnOpen = true;
        assertPrecommitUnavailable(get(OWNER_ID, FILE_ID));

        state.failOnOpen = false;
        long overLimit = 16L * 1024L * 1024L + 1;
        state.metadata = metadata(OWNER_ID, overLimit, sha256(NORMAL_BYTES));
        assertPrecommitUnavailable(get(OWNER_ID, FILE_ID));
    }

    @Test
    void missingCrossOwnerAndOwnerNullRemainIndistinguishable404s() throws Exception {
        HttpResponse<byte[]> crossOwner = get("43", FILE_ID);
        HttpResponse<byte[]> missing = get(OWNER_ID, "missing");
        state.metadata.setOwnerId(null);
        HttpResponse<byte[]> ownerNull = get(OWNER_ID, FILE_ID);

        assertThat(crossOwner.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(ownerNull.statusCode()).isEqualTo(404);
        assertThat(crossOwner.body()).isEqualTo(missing.body()).isEqualTo(ownerNull.body());
    }

    @Test
    void clientAbortClosesAndDeletesThePrevalidatedPayload() throws Exception {
        state.objectBytes = new byte[8 * 1024 * 1024];
        java.util.Arrays.fill(state.objectBytes, (byte) 7);
        state.metadata = metadata(OWNER_ID, state.objectBytes.length, sha256(state.objectBytes));

        HttpResponse<InputStream> response = getStream(OWNER_ID, FILE_ID);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().readNBytes(1024)).hasSize(1024);
        response.body().close();

        awaitPayloadCount(0);
    }

    @Test
    void gracefulRestartAndStartupSweepLeaveNoPayloadResidue() throws Exception {
        assertThat(get(OWNER_ID, FILE_ID).statusCode()).isEqualTo(200);
        awaitPayloadCount(0);
        closeContext();
        assertThat(payloadCount()).isZero();

        Path stale = temporaryDirectory.resolve("staging")
                .resolve("instance-" + UUID.randomUUID());
        Files.createDirectories(stale);
        Files.writeString(stale.resolve(".lock"), "stale", StandardCharsets.UTF_8);
        Files.write(stale.resolve("payload-stale.tmp"), NORMAL_BYTES);
        assertThat(payloadCount()).isEqualTo(1);

        startContext();
        state = context.getBean(TestState.class);
        state.reset();
        assertThat(Files.exists(stale)).isFalse();
        assertThat(get(OWNER_ID, FILE_ID).statusCode()).isEqualTo(200);
    }

    private void assertPrecommitUnavailable(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isNotEqualTo(state.objectBytes);
        assertThat(response.headers().firstValue("ETag")).isEmpty();
        assertThat(response.headers().firstValue("Digest")).isEmpty();
        assertThat(response.headers().firstValue("X-Content-SHA256")).isEmpty();
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("OWNED_FILE_UNAVAILABLE");
    }

    private HttpResponse<byte[]> get(String ownerId, String fileId) throws Exception {
        return client.send(request(ownerId, fileId), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<InputStream> getStream(String ownerId, String fileId) throws Exception {
        return client.send(request(ownerId, fileId), HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpRequest request(String ownerId, String fileId) {
        return HttpRequest.newBuilder(URI.create(baseUri() + "/api/files/owned/" + fileId + "/content"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + JwtUtils.generateToken(
                        Long.parseLong(ownerId), "synthetic", "user"))
                .GET().build();
    }

    private String baseUri() {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return "http://127.0.0.1:" + port;
    }

    private void startContext() {
        SpringApplication application = new SpringApplicationBuilder(HttpTestApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(Map.of("spring.main.banner-mode", "off"))
                .build();
        context = application.run(
                "--server.port=0",
                "--spring.cloud.discovery.enabled=false",
                "--spring.cloud.service-registry.auto-registration.enabled=false",
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--spring.autoconfigure.exclude="
                        + "com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration,"
                        + "com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration",
                "--file.owned-download.staging-directory="
                        + temporaryDirectory.resolve("staging"),
                "--file.owned-download.maximum-bytes=" + (16L * 1024L * 1024L));
    }

    private void closeContext() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private void awaitPayloadCount(long expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        long actual;
        do {
            actual = payloadCount();
            if (actual == expected) {
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(actual).isEqualTo(expected);
    }

    private long payloadCount() throws IOException {
        Path root = temporaryDirectory.resolve("staging");
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("payload-"))
                    .count();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static FileMetadata metadata(String ownerId, long size, String digest) {
        FileMetadata value = new FileMetadata();
        value.setOwnerId(ownerId);
        value.setFileId(FILE_ID);
        value.setStatus("ACTIVE");
        value.setOriginalFileName("material.pdf");
        value.setFileType("application/pdf");
        value.setFileSize(size);
        value.setSha256(digest);
        value.setStorageVersion("sha256:" + digest);
        value.setContentSignature("PDF");
        value.setStorageType("local");
        value.setObjectKey("00000000-0000-0000-0000-000000000000.pdf");
        value.setCreateTime(LocalDateTime.now());
        value.setUpdateTime(LocalDateTime.now());
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            RabbitAutoConfiguration.class
    })
    @EnableConfigurationProperties(OwnedFileDownloadProperties.class)
    @Import({
            OwnerAwareFileController.class,
            OwnerAwareFileExceptionHandler.class,
            AuthenticatedFileOwner.class,
            JwtAuthenticationFilter.class,
            SecurityConfig.class,
            OwnedFileDownloadStaging.class,
            OwnerAwareFileServiceImpl.class
    })
    static class HttpTestApplication {
        @Bean
        TestState testState() {
            return new TestState();
        }

        @Bean
        FileMetadataMapper fileMetadataMapper(TestState state) {
            FileMetadataMapper mapper = mock(FileMetadataMapper.class);
            when(mapper.selectOwnedActiveByFileId(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        String owner = invocation.getArgument(0);
                        String fileId = invocation.getArgument(1);
                        FileMetadata metadata = state.metadata;
                        return metadata != null
                                && owner.equals(metadata.getOwnerId())
                                && fileId.equals(metadata.getFileId())
                                ? metadata : null;
                    });
            return mapper;
        }

        @Bean
        OwnedFileObjectStore ownedFileObjectStore(TestState state) {
            return new OwnedFileObjectStore() {
                @Override
                public StoredObject store(String objectKey, InputStream input, long expectedSize, String contentType) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public InputStream open(FileMetadata metadata) {
                    if (state.failOnOpen) {
                        throw new OwnedFileUnavailableException("synthetic storage open failure");
                    }
                    if (!state.failDuringRead) {
                        return new ByteArrayInputStream(state.objectBytes);
                    }
                    return new FailingInputStream(state.objectBytes);
                }

                @Override
                public void delete(FileMetadata metadata) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Bean
        FileStorageConfig fileStorageConfig() {
            return mock(FileStorageConfig.class);
        }
    }

    static final class TestState {
        private volatile FileMetadata metadata;
        private volatile byte[] objectBytes;
        private volatile boolean failOnOpen;
        private volatile boolean failDuringRead;

        void reset() {
            objectBytes = NORMAL_BYTES.clone();
            metadata = metadata(OWNER_ID, objectBytes.length, sha256(objectBytes));
            failOnOpen = false;
            failDuringRead = false;
        }
    }

    private static final class FailingInputStream extends InputStream {
        private final byte[] bytes;
        private int position;

        private FailingInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() throws IOException {
            if (position >= bytes.length / 2) {
                throw new IOException("synthetic storage read failure");
            }
            return bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= bytes.length / 2) {
                throw new IOException("synthetic storage read failure");
            }
            int read = Math.min(length, Math.max(1, bytes.length / 2 - position));
            System.arraycopy(bytes, position, buffer, offset, read);
            position += read;
            return read;
        }
    }
}
