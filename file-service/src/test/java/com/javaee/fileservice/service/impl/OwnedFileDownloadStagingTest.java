package com.javaee.fileservice.service.impl;

import com.javaee.fileservice.config.OwnedFileDownloadProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OwnedFileDownloadStagingTest {
    @TempDir Path temporaryDirectory;

    @Test
    void stagedPayloadIsOwnerOnlyAndDeletedOnCloseAndShutdown() throws Exception {
        byte[] bytes = "bounded-staged-payload".getBytes(StandardCharsets.UTF_8);
        OwnedFileDownloadProperties properties = new OwnedFileDownloadProperties();
        Path root = temporaryDirectory.resolve("staging");
        properties.setStagingDirectory(root);
        properties.setMaximumBytes(1024);
        OwnedFileDownloadStaging staging = new OwnedFileDownloadStaging(properties);
        staging.initialize();

        InputStream input = staging.stage(
                new ByteArrayInputStream(bytes), bytes.length, sha256(bytes));
        Path instance = onlyChildDirectory(root);
        Path payload = onlyPayload(instance);
        assertOwnerOnly(root, true);
        assertOwnerOnly(instance, true);
        assertOwnerOnly(payload, false);
        assertThat(input.readAllBytes()).isEqualTo(bytes);

        input.close();
        assertThat(Files.exists(payload)).isFalse();
        staging.close();
        assertThat(Files.exists(instance)).isFalse();
    }

    private static Path onlyChildDirectory(Path root) throws Exception {
        try (var paths = Files.list(root)) {
            var matches = paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .toList();
            assertThat(matches).hasSize(1);
            return matches.get(0);
        }
    }

    private static Path onlyPayload(Path instance) throws Exception {
        try (var paths = Files.list(instance)) {
            var matches = paths.filter(path -> path.getFileName().toString().startsWith("payload-"))
                    .toList();
            assertThat(matches).hasSize(1);
            return matches.get(0);
        }
    }

    private static void assertOwnerOnly(Path path, boolean directory) throws Exception {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> expected = directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            assertThat(posix.readAttributes().permissions()).isEqualTo(expected);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(acl).isNotNull();
        String owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
        assertThat(acl.getAcl())
                .isNotEmpty()
                .allMatch(entry -> entry.type() == AclEntryType.ALLOW
                        && entry.principal().getName().equals(owner));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
