package com.javaee.aiservice.review.m0.file;

import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.archive.ZipArchiveEntryOpener;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOwnedFileByteSourceTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private byte[] bytes;
    private String digest;
    private boolean malformedDigest;

    @BeforeEach
    void start() throws Exception {
        bytes = "%PDF-1.7\nowner-aware".getBytes(StandardCharsets.UTF_8);
        digest = sha256(bytes);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/files/owned/file-1/metadata", this::metadata);
        server.createContext("/api/files/owned/file-1/content", this::content);
        server.start();
        authenticate("42", "Bearer trusted-token");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void productionAdapterReadsSameOwnerMetadataAndDigestBoundBytes() throws Exception {
        VerifiedReviewM0FileAccess access = access(baseUri());
        FileArtifactSnapshot snapshot = access.getOwnedFile("42", "file-1");

        assertThat(snapshot.originalFileName()).isEqualTo("原件.pdf");
        assertThat(snapshot.contentDigest()).isEqualTo(digest);
        assertThat(snapshot.sizeBytes()).isEqualTo(bytes.length);
        try (var input = access.openFile("42", "file-1")) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
        // Explicit metadata, pre-read snapshot, content, and post-read immutable-facts check.
        assertThat(requests).hasValue(4);
    }

    @Test
    void ownerMismatchIsHiddenAndNoRequestIsSent() {
        VerifiedReviewM0FileAccess access = access(baseUri());

        assertThatThrownBy(() -> access.getOwnedFile("43", "file-1"))
                .isInstanceOf(OwnedFileAccessException.class)
                .extracting(error -> ((OwnedFileAccessException) error).kind())
                .isEqualTo(OwnedFileAccessException.Kind.NOT_FOUND);
        assertThat(requests).hasValue(0);
    }

    @Test
    void forgedUserHeaderWithoutVerifiedPrincipalCannotDelegate() {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThatThrownBy(() -> access(baseUri()).getOwnedFile("42", "file-1"))
                .isInstanceOf(OwnedFileAccessException.class)
                .extracting(error -> ((OwnedFileAccessException) error).kind())
                .isEqualTo(OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE);
        assertThat(requests).hasValue(0);
    }

    @Test
    void malformedDigestAndMissingIntegrityHeadersFailClosed() {
        malformedDigest = true;
        assertThatThrownBy(() -> access(baseUri()).getOwnedFile("42", "file-1"))
                .isInstanceOf(OwnedFileAccessException.class)
                .extracting(error -> ((OwnedFileAccessException) error).kind())
                .isEqualTo(OwnedFileAccessException.Kind.INTEGRITY_MISMATCH);
    }

    @Test
    void unavailableServiceReturnsFrozen503KindWithoutSyntheticBytes() {
        URI uri = baseUri();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> access(uri).openFile("42", "file-1"))
                .isInstanceOf(OwnedFileAccessException.class)
                .extracting(error -> ((OwnedFileAccessException) error).kind())
                .isEqualTo(OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE);
    }

    private VerifiedReviewM0FileAccess access(URI base) {
        return new VerifiedReviewM0FileAccess(
                new HttpOwnedFileByteSource(base, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                new ZipArchiveEntryOpener(ArchiveSafetyLimits.secureDefaults()));
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void metadata(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"Bearer trusted-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        String responseDigest = malformedDigest ? "not-a-digest" : digest;
        String json = """
                {"fileId":"file-1","originalFileName":"原件.pdf",
                 "declaredContentType":"application/pdf","sizeBytes":%d,
                 "contentDigest":"%s","storageVersion":"sha256:%s",
                 "contentSignature":"PDF","available":true}
                """.formatted(bytes.length, responseDigest, digest);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void content(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        exchange.getResponseHeaders().set("X-Content-SHA256", digest);
        exchange.getResponseHeaders().set("X-Content-Type-Signature", "PDF");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void authenticate(String owner, String bearer) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", bearer);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
