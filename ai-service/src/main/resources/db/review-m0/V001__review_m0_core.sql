CREATE TABLE IF NOT EXISTS review_m0_schema_version (
    version INT NOT NULL PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    installed_at DATETIME(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS review_m0_case (
    case_id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    title VARCHAR(255) NOT NULL,
    processing_mode VARCHAR(32) NOT NULL,
    basis_revision BIGINT NOT NULL,
    checklist_revision BIGINT NOT NULL,
    material_set_revision BIGINT NOT NULL,
    assignment_revision BIGINT NOT NULL,
    name_revision BIGINT NOT NULL,
    version BIGINT NOT NULL,
    current_checklist_snapshot_id VARCHAR(64) NULL,
    current_report_id VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT ck_review_m0_case_processing_mode CHECK (processing_mode IN ('LOCAL_ONLY', 'REMOTE_MODEL')),
    CONSTRAINT ck_review_m0_case_revisions CHECK (
        basis_revision >= 0 AND checklist_revision >= 0 AND material_set_revision >= 0
        AND assignment_revision >= 0 AND name_revision >= 0 AND version >= 0
    ),
    INDEX idx_review_m0_case_owner_list (user_id, updated_at, case_id),
    INDEX idx_review_m0_case_current_checklist (current_checklist_snapshot_id),
    INDEX idx_review_m0_case_current_report (current_report_id)
);

CREATE TABLE IF NOT EXISTS review_m0_official_basis (
    basis_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    file_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    original_file_name_snapshot VARCHAR(1024) NOT NULL,
    declared_content_type VARCHAR(255) NULL,
    file_size_bytes BIGINT NOT NULL,
    file_digest CHAR(64) NOT NULL,
    storage_version VARCHAR(255) NULL,
    cannot_confirm_reason VARCHAR(64) NULL,
    failure_stage VARCHAR(128) NULL,
    attached_basis_revision BIGINT NOT NULL,
    detached_basis_revision BIGINT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_basis_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT uk_review_m0_basis_case_id UNIQUE (case_id, basis_id),
    CONSTRAINT ck_review_m0_basis_role CHECK (role IN ('APPLICATION_NOTICE', 'FILLING_GUIDE', 'OFFICIAL_TEMPLATE')),
    CONSTRAINT ck_review_m0_basis_status CHECK (status IN ('ATTACHED', 'READY', 'CANNOT_CONFIRM', 'DETACHED')),
    CONSTRAINT ck_review_m0_basis_reason CHECK (
        (status = 'CANNOT_CONFIRM' AND cannot_confirm_reason IS NOT NULL)
        OR (status <> 'CANNOT_CONFIRM' AND cannot_confirm_reason IS NULL)
    ),
    CONSTRAINT ck_review_m0_basis_reason_value CHECK (
        cannot_confirm_reason IS NULL OR cannot_confirm_reason IN (
            'PARSE_FAILED', 'ENCRYPTED', 'CORRUPTED', 'OCR_INSUFFICIENT', 'TYPE_MISMATCH',
            'NESTED_ARCHIVE_NOT_ALLOWED', 'ARCHIVE_ENTRY_UNSUPPORTED',
            'ARCHIVE_SAFETY_LIMIT_EXCEEDED', 'SOURCE_FILE_UNAVAILABLE'
        )
    ),
    INDEX idx_review_m0_basis_status_role (case_id, status, role)
);

CREATE TABLE IF NOT EXISTS review_m0_requirement (
    requirement_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    origin VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_requirement_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT ck_review_m0_requirement_origin CHECK (origin IN ('OFFICIAL_EXTRACTED', 'USER_ADDED')),
    INDEX idx_review_m0_requirement_case (case_id, requirement_id)
);

CREATE TABLE IF NOT EXISTS review_m0_checklist_revision (
    checklist_revision_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    basis_revision BIGINT NOT NULL,
    revision_number BIGINT NOT NULL,
    draft_digest CHAR(64) NOT NULL,
    extraction_job_id VARCHAR(64) NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_checklist_revision_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT uk_review_m0_checklist_revision_number UNIQUE (case_id, revision_number),
    CONSTRAINT ck_review_m0_checklist_revision_lifecycle CHECK (
        lifecycle IN ('CANDIDATE_DRAFT', 'CONFIRMED', 'STALE_BASIS', 'SUPERSEDED')
    ),
    INDEX idx_review_m0_checklist_revision_case (case_id, lifecycle, revision_number)
);

CREATE TABLE IF NOT EXISTS review_m0_requirement_revision (
    requirement_revision_id VARCHAR(64) NOT NULL PRIMARY KEY,
    requirement_id VARCHAR(64) NOT NULL,
    checklist_revision_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(1024) NOT NULL,
    enabled BOOLEAN NOT NULL,
    applicability VARCHAR(32) NOT NULL,
    minimum_count INT NOT NULL,
    file_name_rule_kind VARCHAR(16) NOT NULL,
    file_name_rule_pattern VARCHAR(1024) NULL,
    file_name_rule_case_sensitive BOOLEAN NULL,
    authorship VARCHAR(32) NOT NULL,
    source_refs_json LONGTEXT NOT NULL,
    item_version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_requirement_revision_requirement
        FOREIGN KEY (requirement_id) REFERENCES review_m0_requirement(requirement_id),
    CONSTRAINT fk_review_m0_requirement_revision_checklist
        FOREIGN KEY (checklist_revision_id) REFERENCES review_m0_checklist_revision(checklist_revision_id),
    CONSTRAINT uk_review_m0_requirement_revision UNIQUE (requirement_id, checklist_revision_id),
    CONSTRAINT ck_review_m0_requirement_minimum_count CHECK (minimum_count >= 1),
    CONSTRAINT ck_review_m0_requirement_applicability CHECK (
        applicability IN ('APPLIES', 'DOES_NOT_APPLY', 'NEEDS_USER_DECISION')
    ),
    CONSTRAINT ck_review_m0_requirement_authorship CHECK (authorship IN ('EXTRACTED_TEXT', 'USER_REWRITTEN')),
    CONSTRAINT ck_review_m0_requirement_filename_rule CHECK (
        (file_name_rule_kind = 'NONE' AND file_name_rule_pattern IS NULL AND file_name_rule_case_sensitive IS NULL)
        OR (file_name_rule_kind IN ('EXACT', 'REGEX') AND file_name_rule_pattern IS NOT NULL
            AND file_name_rule_case_sensitive IS NOT NULL)
    ),
    INDEX idx_review_m0_requirement_revision_checklist (checklist_revision_id, enabled, applicability)
);

CREATE TABLE IF NOT EXISTS review_m0_checklist_snapshot (
    checklist_snapshot_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    checklist_revision_id VARCHAR(64) NOT NULL,
    basis_revision BIGINT NOT NULL,
    checklist_revision BIGINT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    snapshot_digest CHAR(64) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    confirmed_by VARCHAR(128) NOT NULL,
    confirmed_at DATETIME(3) NOT NULL,
    superseded_at DATETIME(3) NULL,
    CONSTRAINT fk_review_m0_checklist_snapshot_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT fk_review_m0_checklist_snapshot_revision
        FOREIGN KEY (checklist_revision_id) REFERENCES review_m0_checklist_revision(checklist_revision_id),
    CONSTRAINT uk_review_m0_checklist_snapshot_revision UNIQUE (case_id, checklist_revision),
    CONSTRAINT ck_review_m0_checklist_snapshot_lifecycle CHECK (
        lifecycle IN ('CONFIRMED', 'STALE_BASIS', 'SUPERSEDED')
    ),
    INDEX idx_review_m0_checklist_snapshot_lifecycle (case_id, lifecycle)
);

CREATE TABLE IF NOT EXISTS review_m0_submission_source (
    source_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    file_id VARCHAR(64) NOT NULL,
    original_file_name_snapshot VARCHAR(1024) NOT NULL,
    declared_content_type VARCHAR(255) NULL,
    formal_file_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    file_digest CHAR(64) NOT NULL,
    storage_version VARCHAR(255) NULL,
    attached_material_set_revision BIGINT NOT NULL,
    removed_material_set_revision BIGINT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_submission_source_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT ck_review_m0_submission_source_type CHECK (formal_file_type IN ('PDF', 'DOCX', 'XLSX', 'JPG', 'PNG', 'ZIP')),
    CONSTRAINT ck_review_m0_submission_source_status CHECK (status IN ('ATTACHED', 'REMOVED')),
    INDEX idx_review_m0_submission_source_case (case_id, status, source_id)
);

CREATE TABLE IF NOT EXISTS review_m0_material (
    material_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    normalized_entry_path VARCHAR(2048) NULL,
    original_entry_name VARCHAR(1024) NULL,
    entry_index INT NULL,
    content_digest CHAR(64) NOT NULL,
    formal_file_type VARCHAR(16) NOT NULL,
    submission_file_name VARCHAR(1024) NOT NULL,
    normalized_submission_file_name VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL,
    cannot_confirm_reason VARCHAR(64) NULL,
    failure_stage VARCHAR(128) NULL,
    material_set_revision BIGINT NOT NULL,
    name_revision BIGINT NOT NULL,
    version BIGINT NOT NULL,
    top_level_source_id VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN kind = 'TOP_LEVEL_FILE' THEN source_id ELSE NULL END
    ),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_material_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT fk_review_m0_material_source FOREIGN KEY (source_id) REFERENCES review_m0_submission_source(source_id),
    CONSTRAINT uk_review_m0_material_top_level UNIQUE (top_level_source_id),
    CONSTRAINT uk_review_m0_material_zip_entry UNIQUE (source_id, normalized_entry_path),
    CONSTRAINT ck_review_m0_material_kind CHECK (kind IN ('TOP_LEVEL_FILE', 'ZIP_ENTRY')),
    CONSTRAINT ck_review_m0_material_locator CHECK (
        (kind = 'TOP_LEVEL_FILE' AND normalized_entry_path IS NULL)
        OR (kind = 'ZIP_ENTRY' AND normalized_entry_path IS NOT NULL)
    ),
    CONSTRAINT ck_review_m0_material_type CHECK (formal_file_type IN ('PDF', 'DOCX', 'XLSX', 'JPG', 'PNG')),
    CONSTRAINT ck_review_m0_material_status CHECK (status IN ('AVAILABLE', 'CANNOT_CONFIRM', 'REMOVED')),
    CONSTRAINT ck_review_m0_material_reason CHECK (
        (status = 'CANNOT_CONFIRM' AND cannot_confirm_reason IS NOT NULL)
        OR (status <> 'CANNOT_CONFIRM' AND cannot_confirm_reason IS NULL)
    ),
    CONSTRAINT ck_review_m0_material_reason_value CHECK (
        cannot_confirm_reason IS NULL OR cannot_confirm_reason IN (
            'PARSE_FAILED', 'ENCRYPTED', 'CORRUPTED', 'OCR_INSUFFICIENT', 'TYPE_MISMATCH',
            'NESTED_ARCHIVE_NOT_ALLOWED', 'ARCHIVE_ENTRY_UNSUPPORTED',
            'ARCHIVE_SAFETY_LIMIT_EXCEEDED', 'SOURCE_FILE_UNAVAILABLE'
        )
    ),
    INDEX idx_review_m0_material_case_status (case_id, status, material_id),
    INDEX idx_review_m0_material_source (source_id, material_id)
);

CREATE TABLE IF NOT EXISTS review_m0_material_assignment (
    assignment_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    requirement_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    assignment_revision BIGINT NOT NULL,
    decided_by VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_assignment_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT fk_review_m0_assignment_material FOREIGN KEY (material_id) REFERENCES review_m0_material(material_id),
    CONSTRAINT fk_review_m0_assignment_requirement FOREIGN KEY (requirement_id) REFERENCES review_m0_requirement(requirement_id),
    CONSTRAINT uk_review_m0_assignment_revision UNIQUE (case_id, material_id, requirement_id, assignment_revision),
    CONSTRAINT ck_review_m0_assignment_status CHECK (status IN ('SUGGESTED', 'USER_CONFIRMED', 'REJECTED')),
    INDEX idx_review_m0_assignment_current (case_id, assignment_revision, status)
);

CREATE TABLE IF NOT EXISTS review_m0_upload_confirmation (
    upload_confirmation_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    checklist_snapshot_id VARCHAR(64) NOT NULL,
    material_set_revision BIGINT NOT NULL,
    material_set_digest CHAR(64) NOT NULL,
    confirmed_by VARCHAR(128) NOT NULL,
    confirmed_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_upload_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT fk_review_m0_upload_snapshot FOREIGN KEY (checklist_snapshot_id) REFERENCES review_m0_checklist_snapshot(checklist_snapshot_id),
    CONSTRAINT uk_review_m0_upload_input UNIQUE (case_id, checklist_snapshot_id, material_set_revision, material_set_digest),
    INDEX idx_review_m0_upload_current (case_id, checklist_snapshot_id, material_set_revision)
);

CREATE TABLE IF NOT EXISTS review_m0_remote_preview (
    preview_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    basis_revision BIGINT NOT NULL,
    segments_json LONGTEXT NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_remote_preview_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT ck_review_m0_remote_preview_operation CHECK (operation = 'CHECKLIST_EXTRACTION'),
    CONSTRAINT ck_review_m0_remote_preview_status CHECK (
        status IN ('DRAFT', 'READY_FOR_CONFIRMATION', 'CONFIRMED', 'INVALIDATED', 'CONSUMED')
    ),
    INDEX idx_review_m0_remote_preview_case (case_id, basis_revision, status)
);

CREATE TABLE IF NOT EXISTS review_m0_remote_consent (
    consent_id VARCHAR(64) NOT NULL PRIMARY KEY,
    preview_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    basis_revision BIGINT NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    confirmed_by VARCHAR(128) NOT NULL,
    confirmed_at DATETIME(3) NOT NULL,
    consumed_by_job_id VARCHAR(64) NULL,
    consumed_at DATETIME(3) NULL,
    CONSTRAINT fk_review_m0_remote_consent_preview FOREIGN KEY (preview_id) REFERENCES review_m0_remote_preview(preview_id),
    CONSTRAINT fk_review_m0_remote_consent_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT uk_review_m0_remote_consent_job UNIQUE (consumed_by_job_id),
    CONSTRAINT ck_review_m0_remote_consent_operation CHECK (operation = 'CHECKLIST_EXTRACTION'),
    CONSTRAINT ck_review_m0_remote_consent_consumption CHECK (
        (consumed_by_job_id IS NULL AND consumed_at IS NULL)
        OR (consumed_by_job_id IS NOT NULL AND consumed_at IS NOT NULL)
    ),
    INDEX idx_review_m0_remote_consent_case (case_id, basis_revision, confirmed_at)
);

CREATE TABLE IF NOT EXISTS review_m0_input_diagnostic (
    diagnostic_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    basis_id VARCHAR(64) NULL,
    source_id VARCHAR(64) NULL,
    material_id VARCHAR(64) NULL,
    cannot_confirm_reason VARCHAR(64) NOT NULL,
    failure_stage VARCHAR(128) NOT NULL,
    locator_json LONGTEXT NULL,
    evidence_digest CHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_diagnostic_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT fk_review_m0_diagnostic_basis FOREIGN KEY (basis_id) REFERENCES review_m0_official_basis(basis_id),
    CONSTRAINT fk_review_m0_diagnostic_source FOREIGN KEY (source_id) REFERENCES review_m0_submission_source(source_id),
    CONSTRAINT fk_review_m0_diagnostic_material FOREIGN KEY (material_id) REFERENCES review_m0_material(material_id),
    CONSTRAINT ck_review_m0_diagnostic_subject CHECK (
        (subject_type = 'OFFICIAL_BASIS' AND basis_id IS NOT NULL AND source_id IS NULL AND material_id IS NULL)
        OR (subject_type = 'SUBMISSION_SOURCE' AND basis_id IS NULL AND source_id IS NOT NULL AND material_id IS NULL)
        OR (subject_type = 'MATERIAL' AND basis_id IS NULL AND source_id IS NULL AND material_id IS NOT NULL)
    ),
    CONSTRAINT ck_review_m0_diagnostic_reason CHECK (cannot_confirm_reason IN (
        'PARSE_FAILED', 'ENCRYPTED', 'CORRUPTED', 'OCR_INSUFFICIENT', 'TYPE_MISMATCH',
        'NESTED_ARCHIVE_NOT_ALLOWED', 'ARCHIVE_ENTRY_UNSUPPORTED',
        'ARCHIVE_SAFETY_LIMIT_EXCEEDED', 'SOURCE_FILE_UNAVAILABLE'
    )),
    INDEX idx_review_m0_diagnostic_case (case_id, subject_type, created_at)
);

CREATE TABLE IF NOT EXISTS review_m0_idempotency (
    owner_id VARCHAR(128) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    canonical_path VARCHAR(1024) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    response_status INT NULL,
    response_type VARCHAR(64) NULL,
    response_id VARCHAR(64) NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (owner_id, http_method, canonical_path, idempotency_key),
    CONSTRAINT ck_review_m0_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_review_m0_idempotency_response CHECK (
        (state = 'IN_PROGRESS' AND response_status IS NULL AND response_type IS NULL AND response_id IS NULL)
        OR (state = 'COMPLETED' AND response_status IS NOT NULL AND response_type IS NOT NULL AND response_id IS NOT NULL)
    ),
    INDEX idx_review_m0_idempotency_response (owner_id, response_type, response_id)
);

CREATE TABLE IF NOT EXISTS review_m0_job (
    job_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_json LONGTEXT NOT NULL,
    request_digest CHAR(64) NOT NULL,
    input_fingerprint CHAR(64) NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    progress_completed BIGINT NOT NULL,
    progress_total BIGINT NOT NULL,
    attempt INT NOT NULL,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at DATETIME(3) NULL,
    cancel_requested_at DATETIME(3) NULL,
    result_type VARCHAR(64) NULL,
    result_id VARCHAR(64) NULL,
    error_code VARCHAR(64) NULL,
    error_retryable BOOLEAN NULL,
    error_message VARCHAR(1024) NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    heartbeat_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    CONSTRAINT fk_review_m0_job_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT uk_review_m0_job_idempotency UNIQUE (user_id, case_id, job_type, idempotency_key),
    CONSTRAINT ck_review_m0_job_type CHECK (job_type IN ('CHECKLIST_EXTRACTION', 'FORMAL_REVIEW', 'INCREMENTAL_REVIEW')),
    CONSTRAINT ck_review_m0_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_review_m0_job_progress CHECK (progress_completed >= 0 AND progress_total >= 0 AND progress_completed <= progress_total),
    CONSTRAINT ck_review_m0_job_result_error CHECK (
        (status = 'SUCCEEDED' AND result_type IS NOT NULL AND result_id IS NOT NULL
            AND error_code IS NULL AND error_retryable IS NULL AND error_message IS NULL)
        OR (status = 'FAILED' AND result_type IS NULL AND result_id IS NULL
            AND error_code IS NOT NULL AND error_retryable IS NOT NULL AND error_message IS NOT NULL)
        OR (status NOT IN ('SUCCEEDED', 'FAILED') AND result_type IS NULL AND result_id IS NULL
            AND error_code IS NULL AND error_retryable IS NULL AND error_message IS NULL)
    ),
    CONSTRAINT ck_review_m0_job_finished CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL
            AND lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at IS NULL)
    ),
    INDEX idx_review_m0_job_lease (status, lease_expires_at, created_at),
    INDEX idx_review_m0_job_case (case_id, created_at),
    INDEX idx_review_m0_job_owner (user_id, case_id, job_id)
);

CREATE TABLE IF NOT EXISTS review_m0_job_checkpoint (
    job_id VARCHAR(64) NOT NULL,
    checkpoint_key VARCHAR(255) NOT NULL,
    checkpoint_json LONGTEXT NOT NULL,
    checkpoint_digest CHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (job_id, checkpoint_key),
    CONSTRAINT fk_review_m0_checkpoint_job FOREIGN KEY (job_id) REFERENCES review_m0_job(job_id)
);

CREATE TABLE IF NOT EXISTS review_m0_remote_call (
    remote_call_id VARCHAR(64) NOT NULL PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL,
    consent_id VARCHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    provider_request_id VARCHAR(255) NULL,
    response_digest CHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_remote_call_job FOREIGN KEY (job_id) REFERENCES review_m0_job(job_id),
    CONSTRAINT fk_review_m0_remote_call_consent FOREIGN KEY (consent_id) REFERENCES review_m0_remote_consent(consent_id),
    CONSTRAINT uk_review_m0_remote_call_job UNIQUE (job_id),
    CONSTRAINT ck_review_m0_remote_call_state CHECK (state IN ('SEND_INTENT', 'RESPONSE_RECORDED', 'OUTCOME_UNKNOWN')),
    CONSTRAINT ck_review_m0_remote_call_response CHECK (
        (state = 'RESPONSE_RECORDED' AND response_digest IS NOT NULL)
        OR (state <> 'RESPONSE_RECORDED' AND response_digest IS NULL)
    )
);

CREATE TABLE IF NOT EXISTS review_m0_report (
    report_id VARCHAR(64) NOT NULL PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    job_id VARCHAR(64) NOT NULL,
    contract_version VARCHAR(64) NOT NULL,
    engine_version VARCHAR(128) NOT NULL,
    freshness VARCHAR(16) NOT NULL,
    assessment VARCHAR(32) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    checklist_snapshot_id VARCHAR(64) NOT NULL,
    upload_confirmation_id VARCHAR(64) NOT NULL,
    basis_revision BIGINT NOT NULL,
    material_set_revision BIGINT NOT NULL,
    assignment_revision BIGINT NOT NULL,
    name_revision BIGINT NOT NULL,
    base_report_id VARCHAR(64) NULL,
    missing_count INT NOT NULL,
    file_name_noncompliant_count INT NOT NULL,
    cannot_confirm_count INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    outdated_at DATETIME(3) NULL,
    outdated_reason VARCHAR(128) NULL,
    CONSTRAINT fk_review_m0_report_case FOREIGN KEY (case_id) REFERENCES review_m0_case(case_id),
    CONSTRAINT fk_review_m0_report_job FOREIGN KEY (job_id) REFERENCES review_m0_job(job_id),
    CONSTRAINT fk_review_m0_report_snapshot FOREIGN KEY (checklist_snapshot_id) REFERENCES review_m0_checklist_snapshot(checklist_snapshot_id),
    CONSTRAINT fk_review_m0_report_upload FOREIGN KEY (upload_confirmation_id) REFERENCES review_m0_upload_confirmation(upload_confirmation_id),
    CONSTRAINT fk_review_m0_report_base FOREIGN KEY (base_report_id) REFERENCES review_m0_report(report_id),
    CONSTRAINT uk_review_m0_report_job UNIQUE (job_id),
    CONSTRAINT uk_review_m0_report_input UNIQUE (case_id, input_fingerprint),
    CONSTRAINT ck_review_m0_report_freshness CHECK (freshness IN ('CURRENT', 'OUTDATED')),
    CONSTRAINT ck_review_m0_report_assessment CHECK (assessment IN ('M0_READY', 'M0_NOT_READY')),
    CONSTRAINT ck_review_m0_report_counts CHECK (missing_count >= 0 AND file_name_noncompliant_count >= 0 AND cannot_confirm_count >= 0),
    CONSTRAINT ck_review_m0_report_ready CHECK (
        assessment <> 'M0_READY'
        OR (missing_count = 0 AND file_name_noncompliant_count = 0 AND cannot_confirm_count = 0)
    ),
    CONSTRAINT ck_review_m0_report_outdated CHECK (
        (freshness = 'CURRENT' AND outdated_at IS NULL AND outdated_reason IS NULL)
        OR (freshness = 'OUTDATED' AND outdated_at IS NOT NULL AND outdated_reason IS NOT NULL)
    ),
    INDEX idx_review_m0_report_freshness (case_id, freshness, created_at)
);

CREATE TABLE IF NOT EXISTS review_m0_finding (
    finding_id VARCHAR(64) NOT NULL PRIMARY KEY,
    report_id VARCHAR(64) NOT NULL,
    finding_type VARCHAR(32) NOT NULL,
    cannot_confirm_reason VARCHAR(64) NULL,
    requirement_id VARCHAR(64) NULL,
    material_id VARCHAR(64) NULL,
    stable_finding_key CHAR(64) NOT NULL,
    message VARCHAR(2048) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_finding_report FOREIGN KEY (report_id) REFERENCES review_m0_report(report_id),
    CONSTRAINT fk_review_m0_finding_requirement FOREIGN KEY (requirement_id) REFERENCES review_m0_requirement(requirement_id),
    CONSTRAINT fk_review_m0_finding_material FOREIGN KEY (material_id) REFERENCES review_m0_material(material_id),
    CONSTRAINT uk_review_m0_finding_stable UNIQUE (report_id, stable_finding_key),
    CONSTRAINT ck_review_m0_finding_type CHECK (finding_type IN ('MISSING', 'FILE_NAME_NONCOMPLIANT', 'CANNOT_CONFIRM')),
    CONSTRAINT ck_review_m0_finding_reason CHECK (
        (finding_type = 'CANNOT_CONFIRM' AND cannot_confirm_reason IS NOT NULL)
        OR (finding_type <> 'CANNOT_CONFIRM' AND cannot_confirm_reason IS NULL)
    ),
    CONSTRAINT ck_review_m0_finding_reason_value CHECK (
        cannot_confirm_reason IS NULL OR cannot_confirm_reason IN (
            'PARSE_FAILED', 'ENCRYPTED', 'CORRUPTED', 'OCR_INSUFFICIENT', 'TYPE_MISMATCH',
            'NESTED_ARCHIVE_NOT_ALLOWED', 'ARCHIVE_ENTRY_UNSUPPORTED',
            'ARCHIVE_SAFETY_LIMIT_EXCEEDED', 'SOURCE_FILE_UNAVAILABLE'
        )
    ),
    INDEX idx_review_m0_finding_report (report_id, finding_type, finding_id)
);

CREATE TABLE IF NOT EXISTS review_m0_evidence_ref (
    evidence_id VARCHAR(64) NOT NULL PRIMARY KEY,
    finding_id VARCHAR(64) NOT NULL,
    evidence_side VARCHAR(16) NOT NULL,
    evidence_kind VARCHAR(32) NOT NULL,
    ref_json LONGTEXT NOT NULL,
    ref_digest CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_evidence_finding FOREIGN KEY (finding_id) REFERENCES review_m0_finding(finding_id),
    CONSTRAINT ck_review_m0_evidence_side CHECK (evidence_side IN ('RULE', 'MATERIAL')),
    CONSTRAINT ck_review_m0_evidence_kind CHECK (
        evidence_kind IN ('OFFICIAL_BASIS_LOCATION', 'USER_RULE_REVISION', 'GLOBAL_M0_POLICY',
                          'MATERIAL_ITEM_SNAPSHOT', 'MATERIAL_SET_SNAPSHOT')
    ),
    INDEX idx_review_m0_evidence_finding (finding_id, evidence_side, evidence_id)
);

CREATE TABLE IF NOT EXISTS review_m0_finding_decision (
    decision_id VARCHAR(64) NOT NULL PRIMARY KEY,
    report_id VARCHAR(64) NOT NULL,
    finding_id VARCHAR(64) NOT NULL,
    decision_type VARCHAR(16) NOT NULL,
    reason VARCHAR(1024) NULL,
    decided_by VARCHAR(128) NOT NULL,
    decided_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_review_m0_decision_report FOREIGN KEY (report_id) REFERENCES review_m0_report(report_id),
    CONSTRAINT fk_review_m0_decision_finding FOREIGN KEY (finding_id) REFERENCES review_m0_finding(finding_id),
    CONSTRAINT uk_review_m0_decision_ignore UNIQUE (finding_id, decision_type),
    CONSTRAINT ck_review_m0_decision_type CHECK (decision_type = 'IGNORE'),
    INDEX idx_review_m0_decision_report (report_id, decided_at)
);
