CREATE TABLE IF NOT EXISTS ai_task_runtime_schema_version (
    version INT NOT NULL PRIMARY KEY
);
CREATE TABLE IF NOT EXISTS ai_agent_run (
    run_id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    async_job_id VARCHAR(64),
    request_json LONGTEXT NOT NULL,
    checkpoint_json LONGTEXT NOT NULL,
    result_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    waiting_reason VARCHAR(64),
    worker_id VARCHAR(64),
    lease_until TIMESTAMP(3) NULL,
    epoch BIGINT NOT NULL DEFAULT 0,
    attempt INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    UNIQUE KEY uq_agent_request(user_id, client_request_id),
    UNIQUE KEY uq_agent_async(async_job_id)
);
CREATE TABLE IF NOT EXISTS ai_agent_operation (
    run_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    params_digest CHAR(64) NOT NULL,
    replay_safe BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    epoch BIGINT NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    result_json LONGTEXT NOT NULL,
    approval_hash CHAR(64),
    approval_until TIMESTAMP(3) NULL,
    PRIMARY KEY (run_id, operation_id)
);
INSERT INTO ai_task_runtime_schema_version(version)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM ai_task_runtime_schema_version WHERE version = 1);
