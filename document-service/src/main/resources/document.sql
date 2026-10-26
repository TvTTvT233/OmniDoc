-- 创建文档表
CREATE TABLE IF NOT EXISTS `document` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '文档ID',
  `title` VARCHAR(255) NOT NULL COMMENT '文档标题',
  `content` LONGTEXT COMMENT '已验证版本的权威正文',
  `content_format` VARCHAR(24),
  `content_hash` VARCHAR(64),
  `snapshot_state` VARCHAR(32),
  `parse_state` VARCHAR(24),
  `active_generation_id` VARCHAR(64),
  `index_config_hash` VARCHAR(64),
  `summary` TEXT COMMENT '文档摘要',
  `keywords` TEXT COMMENT '关键词（JSON格式）',
  `file_id` VARCHAR(64) COMMENT '关联文件ID',
  `user_id` BIGINT COMMENT '创建用户ID',
  `bucket_name` VARCHAR(128) COMMENT '文档内容所在MinIO桶',
  `object_name` VARCHAR(512) COMMENT '文档内容在MinIO中的对象名',
  `status` VARCHAR(20) DEFAULT 'active' COMMENT '状态：active-活跃，deleted-已删除',
  `version` INT DEFAULT 1 COMMENT '版本号',
  `category` VARCHAR(50) COMMENT '分类',
  `tags` TEXT COMMENT '标签（JSON格式）',
  `created_by` VARCHAR(64) COMMENT '创建人',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_bucket_name` (`bucket_name`),
  INDEX `idx_status` (`status`),
  INDEX `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档表';

-- 已有数据库升级参考：
-- ALTER TABLE `document` ADD COLUMN `bucket_name` VARCHAR(128) COMMENT '文档内容所在MinIO桶';
-- ALTER TABLE `document` ADD COLUMN `object_name` VARCHAR(512) COMMENT '文档内容在MinIO中的对象名';
-- ALTER TABLE `document` ADD INDEX `idx_bucket_name` (`bucket_name`);

-- 创建文档协作访问表
CREATE TABLE IF NOT EXISTS `document_access` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '访问记录ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '文档ID',
  `bucket_name` VARCHAR(128) NOT NULL COMMENT '文档所在MinIO桶',
  `user_id` BIGINT NOT NULL COMMENT '可访问用户ID',
  `role` VARCHAR(20) NOT NULL DEFAULT 'editor' COMMENT '角色：owner/editor/viewer',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY `uk_document_user` (`document_id`, `user_id`),
  INDEX `idx_user_bucket` (`user_id`, `bucket_name`),
  INDEX `idx_bucket_name` (`bucket_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档协作访问表';

-- 创建文档版本表
CREATE TABLE IF NOT EXISTS `document_version` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '版本ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '所属文档ID',
  `version_number` INT NOT NULL COMMENT '版本号',
  `title` VARCHAR(255) NOT NULL COMMENT '文档标题',
  `content` LONGTEXT COMMENT '不可变版本原始正文',
  `verified_version` INT NULL COMMENT '仅可信新版本填写，旧版本保留NULL',
  `content_format` VARCHAR(24),
  `canonical_text` LONGTEXT,
  `content_hash` VARCHAR(64),
  `normalization_version` VARCHAR(32),
  `parser_version` VARCHAR(32),
  `summary` TEXT COMMENT '文档摘要',
  `keywords` TEXT COMMENT '关键词',
  `change_log` VARCHAR(500) COMMENT '变更日志',
  `created_by` VARCHAR(64) COMMENT '创建人',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY `uk_verified_document_version` (`document_id`, `verified_version`),
  INDEX `idx_document_id` (`document_id`),
  INDEX `idx_version_number` (`version_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档版本表';

-- 创建文档评论表
CREATE TABLE IF NOT EXISTS `document_comment` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '评论ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '所属文档ID',
  `user_id` BIGINT COMMENT '评论用户ID',
  `content` TEXT COMMENT '评论内容',
  `parent_id` VARCHAR(64) COMMENT '父评论ID（回复）',
  `created_by` VARCHAR(64) COMMENT '创建人',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `status` VARCHAR(20) DEFAULT 'active' COMMENT '状态：active-活跃，deleted-已删除',
  INDEX `idx_document_id` (`document_id`),
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档评论表';

-- 创建文档批注表
CREATE TABLE IF NOT EXISTS `document_annotation` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '批注ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '所属文档ID',
  `user_id` BIGINT COMMENT '批注用户ID',
  `line_number` INT COMMENT '批注所在行号',
  `start_offset` INT COMMENT '批注起始位置',
  `end_offset` INT COMMENT '批注结束位置',
  `annotation_type` VARCHAR(50) COMMENT '批注类型：highlight-高亮，comment-注释，note-笔记',
  `content` TEXT COMMENT '批注内容',
  `color` VARCHAR(20) COMMENT '批注颜色',
  `status` VARCHAR(20) DEFAULT 'active' COMMENT '状态：active-活跃，deleted-已删除',
  `created_by` VARCHAR(64) COMMENT '创建人',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX `idx_document_id` (`document_id`),
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_line_number` (`line_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档批注表';

-- Existing databases are upgraded additively by DocumentConsistencySchema.
-- Never deduplicate/delete legacy document_version rows to create this verified-only unique constraint.
CREATE TABLE IF NOT EXISTS document_index_task (
  task_id VARCHAR(64) PRIMARY KEY, idempotency_key VARCHAR(255) NOT NULL,
  document_id VARCHAR(64) NOT NULL, document_version INT NOT NULL,
  operation VARCHAR(16) NOT NULL, content_hash VARCHAR(64), config_hash VARCHAR(64), config_json TEXT,
  state VARCHAR(24) NOT NULL, attempt INT NOT NULL DEFAULT 0,
  generation_id VARCHAR(64), cleanup_generation_ids TEXT,
  lease_token VARCHAR(64), lease_until DATETIME(6), worker_id VARCHAR(128),
  error_code VARCHAR(80), next_attempt_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_index_task_idempotency(idempotency_key),
  KEY idx_index_task_due(state,next_attempt_at), KEY idx_index_task_doc(document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS document_index_generation (
  generation_id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL,
  document_id VARCHAR(64) NOT NULL, document_version INT NOT NULL,
  content_hash VARCHAR(64) NOT NULL, config_hash VARCHAR(64) NOT NULL,
  state VARCHAR(24) NOT NULL, created_at DATETIME(6) NOT NULL,
  KEY idx_generation_doc(document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS document_index_reference (
  reference_id VARCHAR(64) PRIMARY KEY, document_id VARCHAR(64) NOT NULL,
  document_version INT NOT NULL, generation_id VARCHAR(64) NOT NULL,
  segment_id VARCHAR(255) NOT NULL, start_offset INT NOT NULL, end_offset INT NOT NULL,
  segment_hash VARCHAR(64) NOT NULL, content_hash VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_generation_segment(generation_id,segment_id), KEY idx_reference_generation(generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
