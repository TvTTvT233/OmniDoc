-- File service database initialization script.
CREATE DATABASE IF NOT EXISTS `omnidoc` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `omnidoc`;

CREATE TABLE IF NOT EXISTS `file_metadata` (
  `id` VARCHAR(64) NOT NULL COMMENT 'metadata primary key',
  `file_id` VARCHAR(64) NOT NULL COMMENT 'business file id',
  `file_name` VARCHAR(255) NOT NULL COMMENT 'stored file name',
  `original_file_name` VARCHAR(255) DEFAULT NULL COMMENT 'original upload file name',
  `file_path` VARCHAR(1024) DEFAULT NULL COMMENT 'file storage path',
  `file_type` VARCHAR(128) DEFAULT NULL COMMENT 'content type',
  `file_size` BIGINT NOT NULL DEFAULT 0 COMMENT 'file size in bytes',
  `md5` VARCHAR(64) DEFAULT NULL COMMENT 'file md5 checksum',
  `owner_id` VARCHAR(128) DEFAULT NULL COMMENT 'authenticated stable owner; NULL legacy rows are not owner-readable',
  `sha256` CHAR(64) DEFAULT NULL COMMENT 'lower-case SHA-256 for owner-aware files',
  `storage_version` VARCHAR(255) DEFAULT NULL COMMENT 'immutable storage version for owner-aware reads',
  `content_signature` VARCHAR(32) DEFAULT NULL COMMENT 'bounded file signature classification',
  `storage_type` VARCHAR(32) DEFAULT NULL COMMENT 'storage type',
  `bucket_name` VARCHAR(128) DEFAULT NULL COMMENT 'storage bucket name',
  `object_key` VARCHAR(512) DEFAULT NULL COMMENT 'storage object key',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'metadata status',
  `create_by` VARCHAR(64) DEFAULT NULL COMMENT 'creator',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_metadata_file_id` (`file_id`),
  KEY `idx_file_metadata_bucket_status` (`bucket_name`, `status`),
  KEY `idx_file_metadata_file_name` (`file_name`),
  KEY `idx_file_metadata_create_time` (`create_time`),
  KEY `idx_file_metadata_md5` (`md5`),
  KEY `idx_file_metadata_owner_file_status` (`owner_id`, `file_id`, `status`),
  CONSTRAINT `chk_file_metadata_owned_integrity` CHECK (
    `owner_id` IS NULL OR (
      `sha256` REGEXP '^[0-9a-f]{64}$'
      AND `storage_version` IS NOT NULL
      AND `content_signature` IS NOT NULL
      AND `file_type` IS NOT NULL
    )
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='file metadata table';
