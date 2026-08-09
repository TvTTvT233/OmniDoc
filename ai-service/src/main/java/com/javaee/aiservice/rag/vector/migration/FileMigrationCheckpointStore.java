package com.javaee.aiservice.rag.vector.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

/**
 * JSON checkpoint store using write-then-rename so an interruption cannot expose a partial checkpoint.
 */
public final class FileMigrationCheckpointStore implements MigrationCheckpointStore {

    public static final Path DEFAULT_PATH = Path.of("var", "qdrant-migration", "checkpoint.json");

    private final Path checkpointPath;
    private final ObjectMapper objectMapper;

    public FileMigrationCheckpointStore() {
        this(DEFAULT_PATH, new ObjectMapper());
    }

    public FileMigrationCheckpointStore(Path checkpointPath) {
        this(checkpointPath, new ObjectMapper());
    }

    public FileMigrationCheckpointStore(Path checkpointPath, ObjectMapper objectMapper) {
        this.checkpointPath = Objects.requireNonNull(checkpointPath, "checkpointPath must not be null")
                .toAbsolutePath()
                .normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public Optional<MigrationCheckpoint> load() {
        if (!Files.exists(checkpointPath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(checkpointPath.toFile(), MigrationCheckpoint.class));
        } catch (IOException | RuntimeException exception) {
            throw checkpointFailure("Unable to read migration checkpoint", exception);
        }
    }

    @Override
    public void save(MigrationCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Path parent = checkpointPath.getParent();
        if (parent == null) {
            throw checkpointFailure("Migration checkpoint path has no parent", null);
        }
        Path temporary = parent.resolve(checkpointPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            objectMapper.writeValue(temporary.toFile(), checkpoint);
            moveAtomically(temporary, checkpointPath);
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The original sanitized checkpoint failure is more useful than cleanup failure.
            }
            throw checkpointFailure("Unable to save migration checkpoint", exception);
        }
    }

    public Path path() {
        return checkpointPath;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static VectorBackendException checkpointFailure(String safeMessage, Throwable cause) {
        return new VectorBackendException(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_MIGRATION_FAILED,
                safeMessage,
                true,
                cause
        );
    }
}
