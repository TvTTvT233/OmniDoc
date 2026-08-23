CREATE TABLE IF NOT EXISTS review_m0_idempotency_response_snapshot (
    scope_digest CHAR(64) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    canonical_path VARCHAR(1024) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    format_version INT NOT NULL,
    response_body LONGTEXT NOT NULL,
    response_body_bytes BIGINT NOT NULL,
    snapshot_digest CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT ck_review_m0_idempotency_snapshot_format CHECK (format_version = 1),
    CONSTRAINT ck_review_m0_idempotency_snapshot_size CHECK (
        response_body_bytes >= 0 AND response_body_bytes <= 262144
    )
);
