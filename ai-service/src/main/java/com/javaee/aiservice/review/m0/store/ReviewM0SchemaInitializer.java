package com.javaee.aiservice.review.m0.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Component
public class ReviewM0SchemaInitializer implements InitializingBean {
    public static final int SCHEMA_VERSION = 2;
    public static final String V1_RESOURCE = "db/review-m0/V001__review_m0_core.sql";
    public static final String V2_RESOURCE = "db/review-m0/V002__review_m0_cyclic_references.sql";

    private static final Logger log = LoggerFactory.getLogger(ReviewM0SchemaInitializer.class);
    private static final Map<String, String> REQUIRED_COLUMNS = requiredColumns();
    private static final Set<String> REQUIRED_INDEXES = Set.of(
            "idx_review_m0_case_owner_list",
            "idx_review_m0_basis_status_role",
            "uk_review_m0_checklist_revision_number",
            "uk_review_m0_requirement_revision",
            "uk_review_m0_checklist_snapshot_revision",
            "uk_review_m0_material_top_level",
            "uk_review_m0_material_zip_entry",
            "uk_review_m0_assignment_revision",
            "uk_review_m0_upload_input",
            "uk_review_m0_job_idempotency",
            "idx_review_m0_job_lease",
            "uk_review_m0_remote_call_job",
            "uk_review_m0_report_job",
            "uk_review_m0_report_input",
            "uk_review_m0_finding_stable",
            "uk_review_m0_decision_ignore");
    private static final Set<RequiredForeignKey> REQUIRED_V2_FOREIGN_KEYS = Set.of(
            new RequiredForeignKey(
                    "review_m0_case", "current_checklist_snapshot_id",
                    "review_m0_checklist_snapshot", "checklist_snapshot_id"),
            new RequiredForeignKey(
                    "review_m0_case", "current_report_id",
                    "review_m0_report", "report_id"),
            new RequiredForeignKey(
                    "review_m0_checklist_revision", "extraction_job_id",
                    "review_m0_job", "job_id"),
            new RequiredForeignKey(
                    "review_m0_remote_consent", "consumed_by_job_id",
                    "review_m0_job", "job_id"));
    private static final Map<String, String> CYCLIC_REFERENCES = Map.of(
            "fk_review_m0_case_current_checklist",
            "ALTER TABLE review_m0_case ADD CONSTRAINT fk_review_m0_case_current_checklist "
                    + "FOREIGN KEY (current_checklist_snapshot_id) REFERENCES review_m0_checklist_snapshot(checklist_snapshot_id)",
            "fk_review_m0_case_current_report",
            "ALTER TABLE review_m0_case ADD CONSTRAINT fk_review_m0_case_current_report "
                    + "FOREIGN KEY (current_report_id) REFERENCES review_m0_report(report_id)",
            "fk_review_m0_checklist_revision_job",
            "ALTER TABLE review_m0_checklist_revision ADD CONSTRAINT fk_review_m0_checklist_revision_job "
                    + "FOREIGN KEY (extraction_job_id) REFERENCES review_m0_job(job_id)",
            "fk_review_m0_remote_consent_job",
            "ALTER TABLE review_m0_remote_consent ADD CONSTRAINT fk_review_m0_remote_consent_job "
                    + "FOREIGN KEY (consumed_by_job_id) REFERENCES review_m0_job(job_id)");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final ReviewM0PersistenceProperties properties;

    public ReviewM0SchemaInitializer(DataSource dataSource, ReviewM0PersistenceProperties properties) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getSchema().getAutoMigration().isEnabled()) {
            migrate();
        }
        validate();
    }

    public void migrate() {
        if (!versionInstalled(1)) {
            run(V1_RESOURCE);
            installVersion(1, "Review M0 core schema");
        }
        if (!versionInstalled(2)) {
            installCyclicReferences();
            installVersion(2, "Review M0 cyclic references");
        }
        log.info("Review M0 schema is ready at version {}", SCHEMA_VERSION);
    }

    public void validate() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_m0_schema_version", Integer.class);
            Integer minimum = jdbcTemplate.queryForObject(
                    "SELECT MIN(version) FROM review_m0_schema_version", Integer.class);
            Integer maximum = jdbcTemplate.queryForObject(
                    "SELECT MAX(version) FROM review_m0_schema_version", Integer.class);
            if (count == null || count != SCHEMA_VERSION || minimum == null || minimum != 1
                    || maximum == null || maximum != SCHEMA_VERSION) {
                throw invalidSchema("schema version must be exactly 2", null);
            }
            for (Map.Entry<String, String> entry : REQUIRED_COLUMNS.entrySet()) {
                jdbcTemplate.query("SELECT " + entry.getValue() + " FROM " + entry.getKey() + " WHERE 1 = 0",
                        resultSet -> { });
            }
            validateIndexes();
            validateForeignKeys();
        } catch (DataAccessException | SQLException error) {
            throw invalidSchema(
                    "a required table, column, index, or version is missing, or a required foreign key is missing",
                    error);
        }
    }

    private void validateIndexes() throws SQLException {
        Set<String> actual = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : REQUIRED_COLUMNS.keySet()) {
                collectIndexes(metadata, table, actual);
                collectIndexes(metadata, table.toUpperCase(Locale.ROOT), actual);
            }
        }
        jdbcTemplate.query(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE LOWER(table_name) LIKE 'review_m0_%' "
                        + "AND LOWER(table_schema) = LOWER(SCHEMA())",
                resultSet -> { actual.add(resultSet.getString(1)); });
        Set<String> missing = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        missing.addAll(REQUIRED_INDEXES);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw invalidSchema("required indexes are missing: " + missing, null);
        }
    }

    private static void collectIndexes(DatabaseMetaData metadata, String table, Set<String> indexes) throws SQLException {
        try (ResultSet resultSet = metadata.getIndexInfo(null, null, table, false, false)) {
            while (resultSet.next()) {
                String name = resultSet.getString("INDEX_NAME");
                if (name != null) {
                    indexes.add(name);
                }
            }
        }
    }

    private void validateForeignKeys() throws SQLException {
        Set<ForeignKeyMapping> actual = new HashSet<>();
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema;
            try {
                schema = connection.getSchema();
            } catch (SQLException unsupportedSchemaLookup) {
                schema = null;
            }
            for (RequiredForeignKey required : REQUIRED_V2_FOREIGN_KEYS) {
                collectForeignKeys(metadata, catalog, schema, required.foreignTable(), actual);
                collectForeignKeys(metadata, catalog, schema,
                        required.foreignTable().toUpperCase(Locale.ROOT), actual);
                collectForeignKeys(metadata, null, null, required.foreignTable(), actual);
                collectForeignKeys(metadata, null, null,
                        required.foreignTable().toUpperCase(Locale.ROOT), actual);
            }
        }
        Set<RequiredForeignKey> missing = new HashSet<>();
        for (RequiredForeignKey required : REQUIRED_V2_FOREIGN_KEYS) {
            boolean present = actual.stream().anyMatch(candidate -> candidate.matches(required));
            if (!present) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw invalidSchema("required V002 foreign-key mappings or restrictive rules are missing: " + missing, null);
        }
    }

    private static void collectForeignKeys(
            DatabaseMetaData metadata,
            String catalog,
            String schema,
            String table,
            Set<ForeignKeyMapping> foreignKeys
    ) throws SQLException {
        try (ResultSet resultSet = metadata.getImportedKeys(catalog, schema, table)) {
            while (resultSet.next()) {
                foreignKeys.add(new ForeignKeyMapping(
                        normalized(resultSet.getString("FKTABLE_NAME")),
                        normalized(resultSet.getString("FKCOLUMN_NAME")),
                        normalized(resultSet.getString("PKTABLE_NAME")),
                        normalized(resultSet.getString("PKCOLUMN_NAME")),
                        resultSet.getShort("KEY_SEQ"),
                        resultSet.getShort("DELETE_RULE"),
                        resultSet.getShort("UPDATE_RULE")));
            }
        }
    }

    private static String normalized(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }

    private static boolean restrictiveRule(short rule) {
        return rule == DatabaseMetaData.importedKeyNoAction || rule == DatabaseMetaData.importedKeyRestrict;
    }

    private boolean versionInstalled(int version) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_m0_schema_version WHERE version = ?", Integer.class, version);
            return count != null && count == 1;
        } catch (DataAccessException missingSchema) {
            return false;
        }
    }

    private void installVersion(int version, String description) {
        jdbcTemplate.update(
                "INSERT INTO review_m0_schema_version(version, description, installed_at) VALUES (?, ?, ?)",
                version, description, Timestamp.from(Instant.now()));
    }

    private void installCyclicReferences() {
        Set<String> installed = constraintNames();
        boolean noneInstalled = CYCLIC_REFERENCES.keySet().stream().noneMatch(installed::contains);
        if (noneInstalled) {
            run(V2_RESOURCE);
            return;
        }
        CYCLIC_REFERENCES.forEach((name, statement) -> {
            if (!installed.contains(name)) {
                jdbcTemplate.execute(statement);
            }
        });
    }

    private Set<String> constraintNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        jdbcTemplate.query(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE LOWER(table_name) LIKE 'review_m0_%' "
                        + "AND LOWER(table_schema) = LOWER(SCHEMA())",
                resultSet -> { names.add(resultSet.getString(1)); });
        return names;
    }

    private void run(String resource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(resource));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }

    private IllegalStateException invalidSchema(String detail, Throwable cause) {
        return new IllegalStateException(
                "Review M0 persistence is enabled but " + detail + ". Apply " + V1_RESOURCE + " and "
                        + V2_RESOURCE + " or explicitly enable ai.review-m0.persistence.schema.auto-migration.enabled.",
                cause);
    }

    private static Map<String, String> requiredColumns() {
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("review_m0_schema_version", "version, description, installed_at");
        tables.put("review_m0_case", "case_id, user_id, title, processing_mode, basis_revision, checklist_revision, material_set_revision, assignment_revision, name_revision, version, current_checklist_snapshot_id, current_report_id, created_at, updated_at");
        tables.put("review_m0_official_basis", "basis_id, case_id, file_id, role, status, original_file_name_snapshot, declared_content_type, file_size_bytes, file_digest, storage_version, cannot_confirm_reason, failure_stage, attached_basis_revision, detached_basis_revision, version, created_at, updated_at");
        tables.put("review_m0_checklist_revision", "checklist_revision_id, case_id, lifecycle, basis_revision, revision_number, draft_digest, extraction_job_id, created_by, created_at");
        tables.put("review_m0_requirement", "requirement_id, case_id, origin, created_at");
        tables.put("review_m0_requirement_revision", "requirement_revision_id, requirement_id, checklist_revision_id, display_name, enabled, applicability, minimum_count, file_name_rule_kind, file_name_rule_pattern, file_name_rule_case_sensitive, authorship, source_refs_json, item_version, created_at");
        tables.put("review_m0_checklist_snapshot", "checklist_snapshot_id, case_id, checklist_revision_id, basis_revision, checklist_revision, snapshot_json, snapshot_digest, lifecycle, confirmed_by, confirmed_at, superseded_at");
        tables.put("review_m0_submission_source", "source_id, case_id, file_id, original_file_name_snapshot, declared_content_type, formal_file_type, status, file_size_bytes, file_digest, storage_version, attached_material_set_revision, removed_material_set_revision, version, created_at, updated_at");
        tables.put("review_m0_material", "material_id, case_id, source_id, kind, normalized_entry_path, original_entry_name, entry_index, content_digest, formal_file_type, submission_file_name, normalized_submission_file_name, status, cannot_confirm_reason, failure_stage, material_set_revision, name_revision, version, created_at, updated_at");
        tables.put("review_m0_material_assignment", "assignment_id, case_id, material_id, requirement_id, status, assignment_revision, decided_by, created_at");
        tables.put("review_m0_upload_confirmation", "upload_confirmation_id, case_id, checklist_snapshot_id, material_set_revision, material_set_digest, confirmed_by, confirmed_at");
        tables.put("review_m0_remote_preview", "preview_id, case_id, operation, status, basis_revision, segments_json, payload_digest, version, created_by, created_at, updated_at");
        tables.put("review_m0_remote_consent", "consent_id, preview_id, case_id, operation, basis_revision, payload_digest, confirmed_by, confirmed_at, consumed_by_job_id, consumed_at");
        tables.put("review_m0_input_diagnostic", "diagnostic_id, case_id, subject_type, basis_id, source_id, material_id, cannot_confirm_reason, failure_stage, locator_json, evidence_digest, created_at");
        tables.put("review_m0_job", "job_id, case_id, user_id, job_type, status, request_json, request_digest, input_fingerprint, idempotency_key, progress_completed, progress_total, attempt, lease_owner, lease_expires_at, cancel_requested_at, result_type, result_id, error_code, error_retryable, error_message, version, created_at, started_at, heartbeat_at, finished_at");
        tables.put("review_m0_job_checkpoint", "job_id, checkpoint_key, checkpoint_json, checkpoint_digest, version, updated_at");
        tables.put("review_m0_remote_call", "remote_call_id, job_id, consent_id, request_digest, state, provider_request_id, response_digest, created_at, updated_at");
        tables.put("review_m0_report", "report_id, case_id, job_id, contract_version, engine_version, freshness, assessment, input_fingerprint, checklist_snapshot_id, upload_confirmation_id, basis_revision, material_set_revision, assignment_revision, name_revision, base_report_id, missing_count, file_name_noncompliant_count, cannot_confirm_count, created_at, outdated_at, outdated_reason");
        tables.put("review_m0_finding", "finding_id, report_id, finding_type, cannot_confirm_reason, requirement_id, material_id, stable_finding_key, message, created_at");
        tables.put("review_m0_evidence_ref", "evidence_id, finding_id, evidence_side, evidence_kind, ref_json, ref_digest, created_at");
        tables.put("review_m0_finding_decision", "decision_id, report_id, finding_id, decision_type, reason, decided_by, decided_at");
        tables.put("review_m0_idempotency", "owner_id, http_method, canonical_path, idempotency_key, request_digest, response_status, response_type, response_id, state, created_at, updated_at");
        return Map.copyOf(tables);
    }

    private record RequiredForeignKey(
            String foreignTable,
            String foreignColumn,
            String primaryTable,
            String primaryColumn
    ) {
    }

    private record ForeignKeyMapping(
            String foreignTable,
            String foreignColumn,
            String primaryTable,
            String primaryColumn,
            short keySequence,
            short deleteRule,
            short updateRule
    ) {
        private boolean matches(RequiredForeignKey required) {
            return keySequence == 1
                    && foreignTable.equals(required.foreignTable())
                    && foreignColumn.equals(required.foreignColumn())
                    && primaryTable.equals(required.primaryTable())
                    && primaryColumn.equals(required.primaryColumn())
                    && restrictiveRule(deleteRule)
                    && restrictiveRule(updateRule);
        }
    }
}
