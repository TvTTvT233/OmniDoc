package com.javaee.aiservice.review.m0.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fixed-width database keys derived from unambiguous length-prefixed UTF-8 fields. */
public final class ReviewM0KeyDigests {
    private static final int ENCODING_VERSION = 1;
    private static final String IDEMPOTENCY_SCOPE_DOMAIN = "review-m0/idempotency-scope";
    private static final String ZIP_ENTRY_SCOPE_DOMAIN = "review-m0/zip-entry-scope";

    private ReviewM0KeyDigests() {
    }

    public static String idempotencyScope(
            String ownerId,
            String httpMethod,
            String canonicalPath,
            String idempotencyKey
    ) {
        return digest(IDEMPOTENCY_SCOPE_DOMAIN, ownerId, httpMethod, canonicalPath, idempotencyKey);
    }

    public static String zipEntryScope(String sourceId, String normalizedEntryPath) {
        return digest(ZIP_ENTRY_SCOPE_DOMAIN, sourceId, normalizedEntryPath);
    }

    static byte[] encode(String domain, String... fields) {
        if (domain == null || domain.isEmpty() || fields == null) {
            throw new IllegalArgumentException("Digest domain and fields are required");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(ENCODING_VERSION);
                writeField(output, domain);
                output.writeInt(fields.length);
                for (String field : fields) {
                    if (field == null) {
                        throw new IllegalArgumentException("Digest fields cannot be null");
                    }
                    writeField(output, field);
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossibleForMemoryBuffer) {
            throw new IllegalStateException("Cannot encode Review M0 database key", impossibleForMemoryBuffer);
        }
    }

    private static String digest(String domain, String... fields) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encode(domain, fields)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void writeField(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
