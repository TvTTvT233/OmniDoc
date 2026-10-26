-- Owner-aware file access migration.
-- Historical rows deliberately retain owner_id = NULL and fail closed. There is no inferred
-- backfill from bucket, object key, creator header, or upload event data.
ALTER TABLE file_metadata
  ADD COLUMN owner_id VARCHAR(128) NULL COMMENT 'authenticated stable owner; NULL legacy rows are not owner-readable',
  ADD COLUMN sha256 CHAR(64) NULL COMMENT 'lower-case SHA-256 for owner-aware files',
  ADD COLUMN storage_version VARCHAR(255) NULL COMMENT 'immutable storage version for owner-aware reads',
  ADD COLUMN content_signature VARCHAR(32) NULL COMMENT 'bounded file signature classification';

CREATE INDEX idx_file_metadata_owner_file_status
  ON file_metadata (owner_id, file_id, status);

ALTER TABLE file_metadata
  ADD CONSTRAINT chk_file_metadata_owned_integrity CHECK (
    owner_id IS NULL OR (
      sha256 REGEXP '^[0-9a-f]{64}$'
      AND storage_version IS NOT NULL
      AND content_signature IS NOT NULL
      AND file_type IS NOT NULL
    )
  );
