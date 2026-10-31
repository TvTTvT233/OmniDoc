package com.javaee.fileservice.acceptance;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Runtime-only real MinIO SDK probe for the independent R4-B validation runner. */
public final class R4BMinioSdkProbe {
    private R4BMinioSdkProbe() {}

    public static void main(String[] args) throws Exception {
        run(args);
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 1) fail("action is required");
        String action = args[0];
        if ("purge-bucket".equals(action)) {
            purgeBucket();
            ok(action, -1L, -1L);
            return;
        }
        if ("count-objects".equals(action)) {
            ok(action, countObjects(), -1L);
            return;
        }
        if (args.length < 2) fail("fileId is required");
        FileRow row = fileRow(args[1]);
        switch (action) {
            case "verify" -> {
                if (args.length != 3) fail("verify requires fixture path");
                byte[] expected = Files.readAllBytes(Path.of(args[2]));
                byte[] actual;
                try (InputStream input = client().getObject(GetObjectArgs.builder()
                        .bucket(row.bucket()).object(row.object()).build())) {
                    actual = input.readAllBytes();
                }
                long statSize = client().statObject(StatObjectArgs.builder()
                        .bucket(row.bucket()).object(row.object()).build()).size();
                require(expected.length == row.size(), "database size differs from fixture");
                require(statSize == expected.length, "SDK stat size differs from fixture");
                require(MessageDigest.isEqual(expected, actual), "SDK bytes differ from fixture");
                require(sha256(expected).equals(row.sha256()), "database digest differs from fixture");
                require(row.storageVersion() != null && !row.storageVersion().isBlank(),
                        "storage version is empty");
                ok(action, actual.length, statSize);
            }
            case "replace-equal", "replace-short", "replace-long", "restore" -> {
                if (args.length != 3) fail(action + " requires fixture path");
                byte[] original = Files.readAllBytes(Path.of(args[2]));
                byte[] bytes = switch (action) {
                    case "replace-equal" -> equalMutation(original);
                    case "replace-short" -> java.util.Arrays.copyOf(original, Math.max(0, original.length - 1));
                    case "replace-long" -> java.util.Arrays.copyOf(original, original.length + 1);
                    default -> original;
                };
                client().putObject(PutObjectArgs.builder().bucket(row.bucket()).object(row.object())
                        .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
                ok(action, bytes.length, -1L);
            }
            case "delete-object" -> {
                client().removeObject(RemoveObjectArgs.builder()
                        .bucket(row.bucket()).object(row.object()).build());
                ok(action, 0L, -1L);
            }
            default -> fail("unsupported action");
        }
    }

    private static FileRow fileRow(String fileId) throws Exception {
        String sql = "SELECT bucket_name, object_key, file_size, sha256, storage_version "
                + "FROM file_metadata WHERE file_id=?";
        try (Connection connection = DriverManager.getConnection(required("R4B_JDBC_URL"),
                required("R4B_DB_USER"), required("R4B_DB_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) fail("file row does not exist");
                FileRow row = new FileRow(result.getString(1), result.getString(2),
                        result.getLong(3), result.getString(4), result.getString(5));
                if (result.next()) fail("file row is not unique");
                require(row.bucket() != null && !row.bucket().isBlank(), "bucket is empty");
                require(row.object() != null && !row.object().isBlank(), "object locator is empty");
                return row;
            }
        }
    }

    private static MinioClient client() {
        return MinioClient.builder().endpoint(required("R4B_MINIO_ENDPOINT"))
                .credentials(required("R4B_MINIO_ACCESS"), required("R4B_MINIO_SECRET")).build();
    }

    private static long countObjects() throws Exception {
        long count = 0;
        for (Result<Item> result : client().listObjects(ListObjectsArgs.builder()
                .bucket(required("R4B_MINIO_BUCKET")).recursive(true).build())) {
            result.get();
            count++;
        }
        return count;
    }

    private static void purgeBucket() throws Exception {
        List<DeleteObject> deletes = new ArrayList<>();
        for (Result<Item> result : client().listObjects(ListObjectsArgs.builder()
                .bucket(required("R4B_MINIO_BUCKET")).recursive(true).build())) {
            deletes.add(new DeleteObject(result.get().objectName()));
        }
        if (!deletes.isEmpty()) {
            for (Result<?> result : client().removeObjects(RemoveObjectsArgs.builder()
                    .bucket(required("R4B_MINIO_BUCKET")).objects(deletes).build())) {
                result.get();
            }
        }
    }

    private static byte[] equalMutation(byte[] input) {
        require(input.length > 0, "fixture is empty");
        byte[] mutated = input.clone();
        mutated[mutated.length - 1] ^= 0x01;
        return mutated;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) fail("required runtime environment is missing");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) fail(message);
    }

    private static void ok(String action, long bytes, long statBytes) {
        System.out.printf("{\"status\":\"PASS\",\"action\":\"%s\",\"bytes\":%d,\"statBytes\":%d}%n",
                action, bytes, statBytes);
    }

    private static void fail(String message) {
        throw new IllegalStateException("R4B_SDK_ASSERTION_FAILED " + message);
    }

    private record FileRow(String bucket, String object, long size, String sha256, String storageVersion) {}
}
