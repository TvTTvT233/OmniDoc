package com.javaee.fileservice.service.impl;

import com.javaee.fileservice.config.OwnedFileDownloadProperties;
import com.javaee.fileservice.exception.OwnedFileUnavailableException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * Fully materializes and verifies bytes before the HTTP layer can create a 200 response.
 * Each staged payload is owner-only and deleted when its returned stream closes.
 */
@Component
public final class OwnedFileDownloadStaging implements AutoCloseable {
    private static final Pattern INSTANCE_DIRECTORY =
            Pattern.compile("instance-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final OwnedFileDownloadProperties properties;
    private final Set<ManagedInputStream> activeStreams = ConcurrentHashMap.newKeySet();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private Path rootDirectory;
    private Path instanceDirectory;
    private FileChannel instanceLockChannel;
    private FileLock instanceLock;
    private volatile boolean closed;

    public OwnedFileDownloadStaging(OwnedFileDownloadProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        lifecycleLock.writeLock().lock();
        try {
            if (instanceDirectory != null) {
                return;
            }
            properties.requiredMaximumBytes();
            rootDirectory = properties.requiredStagingDirectory();
            Files.createDirectories(rootDirectory);
            if (Files.isSymbolicLink(rootDirectory)
                    || !Files.isDirectory(rootDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Owned file download staging root is not a dedicated directory");
            }
            restrictOwnerOnly(rootDirectory, true);
            cleanupStaleInstances();
            instanceDirectory = Files.createDirectory(
                    rootDirectory.resolve("instance-" + UUID.randomUUID()));
            restrictOwnerOnly(instanceDirectory, true);
            Path lockPath = instanceDirectory.resolve(".lock");
            instanceLockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictOwnerOnly(lockPath, false);
            instanceLock = instanceLockChannel.lock();
        } catch (Exception error) {
            closeResources();
            deleteTreeQuietly(instanceDirectory);
            instanceDirectory = null;
            throw new IllegalStateException("Owned file download staging is unavailable", error);
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    InputStream stage(InputStream source, long expectedSize, String expectedDigest) {
        lifecycleLock.readLock().lock();
        Path staged = null;
        try {
            ensureOpen();
            if (source == null) {
                throw new OwnedFileUnavailableException("Owned file storage returned no stream");
            }
            long maximumBytes = properties.requiredMaximumBytes();
            if (expectedSize < 0 || expectedSize > maximumBytes
                    || expectedDigest == null || !expectedDigest.matches("[0-9a-f]{64}")) {
                throw new OwnedFileUnavailableException("Owned file bytes exceed the download safety bound");
            }
            staged = Files.createTempFile(instanceDirectory, "payload-", ".tmp");
            restrictOwnerOnly(staged, false);
            MessageDigest digest = sha256();
            long count = 0;
            try (InputStream input = source;
                 OutputStream output = Files.newOutputStream(
                         staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    if (read > expectedSize - count || read > maximumBytes - count) {
                        throw new IntegrityFailure();
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    count += read;
                }
            }
            String actualDigest = HexFormat.of().formatHex(digest.digest());
            if (count != expectedSize || !actualDigest.equals(expectedDigest)) {
                throw new IntegrityFailure();
            }

            ManagedInputStream managed = new ManagedInputStream(
                    Files.newInputStream(staged, StandardOpenOption.READ), staged);
            activeStreams.add(managed);
            return managed;
        } catch (IntegrityFailure error) {
            deleteQuietly(staged);
            throw new OwnedFileUnavailableException("Owned file bytes failed integrity verification");
        } catch (OwnedFileUnavailableException error) {
            deleteQuietly(staged);
            throw error;
        } catch (Exception error) {
            deleteQuietly(staged);
            throw new OwnedFileUnavailableException("Owned file bytes could not be prevalidated", error);
        } finally {
            closeQuietly(source);
            lifecycleLock.readLock().unlock();
        }
    }

    @PreDestroy
    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            for (ManagedInputStream stream : new ArrayList<>(activeStreams)) {
                closeQuietly(stream);
            }
            closeResources();
            deleteTreeQuietly(instanceDirectory);
            instanceDirectory = null;
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed || instanceDirectory == null) {
            throw new OwnedFileUnavailableException("Owned file download staging is unavailable");
        }
    }

    private void cleanupStaleInstances() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(rootDirectory)) {
            for (Path candidate : entries) {
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        || !INSTANCE_DIRECTORY.matcher(candidate.getFileName().toString()).matches()) {
                    continue;
                }
                Path lockPath = candidate.resolve(".lock");
                try (FileChannel channel = FileChannel.open(lockPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    FileLock lock;
                    try {
                        lock = channel.tryLock();
                    } catch (OverlappingFileLockException error) {
                        continue;
                    }
                    if (lock == null) {
                        continue;
                    }
                    lock.release();
                } catch (IOException error) {
                    continue;
                }
                deleteTree(candidate);
            }
        }
    }

    private void closeResources() {
        try {
            if (instanceLock != null && instanceLock.isValid()) {
                instanceLock.release();
            }
        } catch (IOException ignored) {
            // Shutdown cleanup continues without exposing filesystem details.
        } finally {
            instanceLock = null;
        }
        closeQuietly(instanceLockChannel);
        instanceLockChannel = null;
    }

    private void release(ManagedInputStream stream, Path staged) {
        activeStreams.remove(stream);
        deleteQuietly(staged);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void restrictOwnerOnly(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
            return;
        }
        boolean restricted = path.toFile().setReadable(false, false)
                && path.toFile().setWritable(false, false)
                && path.toFile().setExecutable(false, false)
                && path.toFile().setReadable(true, true)
                && path.toFile().setWritable(true, true)
                && (!directory || path.toFile().setExecutable(true, true));
        if (!restricted) {
            throw new IOException("Owner-only filesystem permissions are unavailable");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path entry : entries) {
                    deleteTree(entry);
                }
            }
        }
        Files.deleteIfExists(root);
    }

    private static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // A later process startup retries cleanup after the OS releases handles.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Shutdown/startup cleanup is the final fallback.
        }
    }

    private static void closeQuietly(AutoCloseable value) {
        try {
            if (value != null) {
                value.close();
            }
        } catch (Exception ignored) {
            // Preserve the primary integrity or shutdown outcome.
        }
    }

    private static final class IntegrityFailure extends RuntimeException { }

    private final class ManagedInputStream extends FilterInputStream {
        private final Path staged;
        private boolean streamClosed;

        private ManagedInputStream(InputStream input, Path staged) {
            super(input);
            this.staged = staged;
        }

        @Override
        public void close() throws IOException {
            if (streamClosed) {
                return;
            }
            streamClosed = true;
            IOException failure = null;
            try {
                super.close();
            } catch (IOException error) {
                failure = error;
            } finally {
                release(this, staged);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
