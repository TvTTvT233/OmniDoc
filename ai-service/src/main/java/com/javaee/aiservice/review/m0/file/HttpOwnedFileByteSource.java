package com.javaee.aiservice.review.m0.file;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Production adapter for file-service's authenticated owner-aware boundary. */
public final class HttpOwnedFileByteSource implements OwnedFileByteSource {
    private static final int MAXIMUM_METADATA_BYTES = 64 * 1024;

    private final URI serviceBaseUri;
    private final Duration requestTimeout;
    private final HttpClient client;
    private final ObjectMapper json;
    private final FileServiceRequestAuthentication authentication;
    private String internalToken = "";

    public HttpOwnedFileByteSource(
            URI serviceBaseUri,
            Duration connectTimeout,
            Duration requestTimeout
    ) {
        this(serviceBaseUri, requestTimeout,
                HttpClient.newBuilder().connectTimeout(connectTimeout).build(),
                new ObjectMapper(), new FileServiceRequestAuthentication());
    }

    public HttpOwnedFileByteSource(URI serviceBaseUri, Duration connectTimeout,
                                  Duration requestTimeout, String internalToken) {
        this(serviceBaseUri, connectTimeout, requestTimeout);
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    HttpOwnedFileByteSource(
            URI serviceBaseUri,
            Duration requestTimeout,
            HttpClient client,
            ObjectMapper json,
            FileServiceRequestAuthentication authentication
    ) {
        this.serviceBaseUri = requireHttpBase(serviceBaseUri);
        this.requestTimeout = java.util.Objects.requireNonNull(requestTimeout);
        this.client = java.util.Objects.requireNonNull(client);
        this.json = java.util.Objects.requireNonNull(json).copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.authentication = java.util.Objects.requireNonNull(authentication);
    }

    @Override
    public FileArtifactSnapshot getOwnedFile(String ownerId, String fileId) {
        HttpRequest request = request(ownerId, fileId, "metadata", "application/json");
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 404) {
                closeQuietly(response.body());
                throw notFound();
            }
            if (response.statusCode() != 200) {
                closeQuietly(response.body());
                throw unavailable("Owner-aware file metadata request failed");
            }
            byte[] body;
            try (InputStream input = response.body()) {
                body = readBounded(input, MAXIMUM_METADATA_BYTES);
            }
            OwnedMetadata metadata = json.readValue(body, OwnedMetadata.class);
            validateMetadata(metadata, fileId);
            return new FileArtifactSnapshot(
                    metadata.fileId(), metadata.originalFileName(),
                    metadata.declaredContentType(), metadata.sizeBytes(),
                    metadata.contentDigest(), metadata.storageVersion(), metadata.available());
        } catch (OwnedFileAccessException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable("Owner-aware file metadata request was interrupted", error);
        } catch (IOException | RuntimeException error) {
            throw integrityOrUnavailable(error, "Owner-aware file metadata is not trustworthy");
        }
    }

    @Override
    public InputStream openOwnedFile(
            String ownerId,
            String fileId,
            FileArtifactSnapshot expectedSnapshot
    ) {
        if (expectedSnapshot == null || !fileId.equals(expectedSnapshot.fileId())) {
            throw integrity("Owned file snapshot does not match the requested fileId", null);
        }
        HttpRequest request = request(ownerId, fileId, "content", "application/octet-stream");
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 404) {
                closeQuietly(response.body());
                throw notFound();
            }
            if (response.statusCode() != 200) {
                closeQuietly(response.body());
                throw unavailable("Owner-aware file content request failed");
            }
            String digest = response.headers().firstValue("X-Content-SHA256").orElse(null);
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            String signature = response.headers().firstValue("X-Content-Type-Signature").orElse(null);
            if (!expectedSnapshot.contentDigest().equals(digest)
                    || expectedSnapshot.sizeBytes() != length
                    || signature == null || signature.isBlank()) {
                closeQuietly(response.body());
                throw integrity("Owner-aware file content headers failed integrity verification", null);
            }
            return response.body();
        } catch (OwnedFileAccessException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable("Owner-aware file content request was interrupted", error);
        } catch (IOException error) {
            throw unavailable("Owner-aware file content request failed", error);
        }
    }

    private HttpRequest request(String ownerId, String fileId, String suffix, String accept) {
        boolean background = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null
                && org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() == null;
        if (background) {
            if (internalToken.length() < 32 || internalToken.length() > 1024
                    || ownerId == null || !ownerId.matches("[A-Za-z0-9_-]{1,64}")) {
                throw unavailable("Authenticated background file-service delegation is unavailable");
            }
            return HttpRequest.newBuilder(resourceUri(fileId, suffix, "/internal/review-files/"))
                    .timeout(requestTimeout).header("X-OmniDoc-Review-Token", internalToken)
                    .header("X-OmniDoc-Review-OwnerId", ownerId).header("Accept", accept).GET().build();
        }
        String bearer = authentication.requireBearerForOwner(ownerId);
        return HttpRequest.newBuilder(resourceUri(fileId, suffix, "/api/files/owned/"))
                .timeout(requestTimeout).header("Authorization", bearer).header("Accept", accept).GET().build();
    }

    private URI resourceUri(String fileId, String suffix, String prefix) {
        String encodedFileId = java.net.URLEncoder.encode(fileId, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return URI.create(serviceBaseUri.toString() + prefix
                + encodedFileId + "/" + suffix);
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new IOException("Metadata response exceeds its safety bound");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateMetadata(OwnedMetadata metadata, String requestedFileId) {
        if (metadata == null || !requestedFileId.equals(metadata.fileId())
                || metadata.contentSignature() == null
                || !metadata.contentSignature().matches("PDF|ZIP_CONTAINER|JPEG|PNG|UNKNOWN")
                || !metadata.available()) {
            throw integrity("Owner-aware file metadata failed integrity verification", null);
        }
        // FileArtifactSnapshot performs the remaining length/digest/type/version validation.
    }

    private static URI requireHttpBase(URI input) {
        if (input == null || input.getScheme() == null
                || !("http".equalsIgnoreCase(input.getScheme())
                || "https".equalsIgnoreCase(input.getScheme()))
                || input.getHost() == null || input.getQuery() != null || input.getFragment() != null) {
            throw new IllegalArgumentException("Review M0 file-service base URI is invalid");
        }
        String value = input.toString().replaceAll("/+$", "");
        return URI.create(value);
    }

    private static OwnedFileAccessException integrityOrUnavailable(Throwable cause, String message) {
        if (cause instanceof IllegalArgumentException) {
            return integrity(message, cause);
        }
        return unavailable(message, cause);
    }

    private static OwnedFileAccessException notFound() {
        return new OwnedFileAccessException(
                OwnedFileAccessException.Kind.NOT_FOUND, "Owned file does not exist");
    }

    private static OwnedFileAccessException unavailable(String message) {
        return new OwnedFileAccessException(
                OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE, message);
    }

    private static OwnedFileAccessException unavailable(String message, Throwable cause) {
        return new OwnedFileAccessException(
                OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE, message, cause);
    }

    private static OwnedFileAccessException integrity(String message, Throwable cause) {
        return new OwnedFileAccessException(
                OwnedFileAccessException.Kind.INTEGRITY_MISMATCH, message, cause);
    }

    private static void closeQuietly(InputStream input) {
        try {
            if (input != null) input.close();
        } catch (IOException ignored) {
            // preserve the mapped boundary error
        }
    }

    private record OwnedMetadata(
            String fileId,
            String originalFileName,
            String declaredContentType,
            long sizeBytes,
            String contentDigest,
            String storageVersion,
            String contentSignature,
            boolean available
    ) { }
}
