package com.javaee.aiservice.rag.vector.migration;

import java.util.Optional;

public interface MigrationCheckpointStore {

    Optional<MigrationCheckpoint> load();

    void save(MigrationCheckpoint checkpoint);
}
