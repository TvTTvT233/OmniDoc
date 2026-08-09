package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.VectorPointIdFactory;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * RFC 9562 UUIDv5 point IDs. Java's {@code UUID.nameUUIDFromBytes} is deliberately not used
 * because it produces UUIDv3 values.
 */
public final class UuidV5VectorPointIdFactory implements VectorPointIdFactory {

    /** UUIDv5(URL namespace, "urn:omnidoc:rag:vector-points"). */
    static final UUID OMNIDOC_NAMESPACE = UUID.fromString("9e20ca0c-77c0-5539-b61e-a7f5d07b5725");

    @Override
    public String create(VectorRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        return create(record.id(), record.scope());
    }

    public String create(String logicalId, VectorScope scope) {
        if (logicalId == null || logicalId.isBlank()) {
            throw new IllegalArgumentException("logicalId must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        String name = scope.userId() + "\n" + scope.knowledgeBaseId() + "\n" + logicalId;
        return uuidV5(OMNIDOC_NAMESPACE, name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static UUID uuidV5(UUID namespace, byte[] name) {
        if (namespace == null) {
            throw new IllegalArgumentException("namespace must not be null");
        }
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required for UUIDv5", exception);
        }
        ByteBuffer namespaceBytes = ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits());
        sha1.update(namespaceBytes.array());
        byte[] hash = sha1.digest(name);
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer result = ByteBuffer.wrap(hash);
        return new UUID(result.getLong(), result.getLong());
    }
}
