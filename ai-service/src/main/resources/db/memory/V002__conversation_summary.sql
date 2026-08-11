CREATE TABLE IF NOT EXISTS ai_conversation_summary (
    conversation_id VARCHAR(64) NOT NULL PRIMARY KEY,
    schema_version VARCHAR(16) NOT NULL,
    summary_version BIGINT NOT NULL,
    from_sequence BIGINT NOT NULL,
    to_sequence BIGINT NOT NULL,
    source_digest CHAR(64) NOT NULL,
    summary_json LONGTEXT NOT NULL,
    generated_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_ai_conversation_summary_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_conversation (conversation_id) ON DELETE CASCADE,
    INDEX idx_ai_conversation_summary_version (conversation_id, summary_version),
    INDEX idx_ai_conversation_summary_coverage (conversation_id, to_sequence)
);
