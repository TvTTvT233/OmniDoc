CREATE TABLE IF NOT EXISTS ai_memory_schema_version (
    version INT NOT NULL PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    installed_at DATETIME(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_conversation (
    conversation_id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    title VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL,
    next_sequence BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    archived_at DATETIME(3) NULL,
    version BIGINT NOT NULL,
    INDEX idx_ai_conversation_owner_list (user_id, status, updated_at, conversation_id),
    INDEX idx_ai_conversation_owner_id (user_id, conversation_id),
    INDEX idx_ai_conversation_version (conversation_id, version)
);

CREATE TABLE IF NOT EXISTS ai_conversation_message (
    message_id VARCHAR(64) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    `sequence` BIGINT NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    turn_ordinal INT NOT NULL,
    role VARCHAR(16) NOT NULL,
    message_type VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    trace_id VARCHAR(64) NULL,
    tool_call_id VARCHAR(128) NULL,
    metadata_json LONGTEXT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_ai_conversation_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_conversation (conversation_id) ON DELETE CASCADE,
    CONSTRAINT uk_ai_conversation_message_sequence UNIQUE (conversation_id, `sequence`),
    CONSTRAINT uk_ai_conversation_message_request UNIQUE
        (conversation_id, client_request_id, role, message_type, turn_ordinal),
    INDEX idx_ai_conversation_message_page (conversation_id, `sequence`)
);
