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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.TreeMap;

@Component
public class ReviewM0SchemaInitializer implements InitializingBean {
    public static final int SCHEMA_VERSION = 3;
    public static final String V1_RESOURCE = "db/review-m0/V001__review_m0_core.sql";
    public static final String V2_RESOURCE = "db/review-m0/V002__review_m0_cyclic_references.sql";
    public static final String V3_RESOURCE = "db/review-m0/V003__review_m0_idempotency_response_snapshot.sql";

    private static final Logger log = LoggerFactory.getLogger(ReviewM0SchemaInitializer.class);
    private static final Map<String, String> REQUIRED_COLUMNS = requiredColumns();
    private static final Set<String> REQUIRED_INDEXES = Set.of(
            "idx_review_m0_case_owner_list",
            "idx_review_m0_case_current_checklist",
            "idx_review_m0_case_current_report",
            "uk_review_m0_basis_case_id",
            "idx_review_m0_basis_status_role",
            "idx_review_m0_requirement_case",
            "uk_review_m0_checklist_revision_number",
            "idx_review_m0_checklist_revision_case",
            "uk_review_m0_requirement_revision",
            "idx_review_m0_requirement_revision_checklist",
            "uk_review_m0_checklist_snapshot_revision",
            "idx_review_m0_checklist_snapshot_lifecycle",
            "idx_review_m0_submission_source_case",
            "uk_review_m0_material_top_level",
            "uk_review_m0_material_zip_entry",
            "idx_review_m0_material_case_status",
            "idx_review_m0_material_source",
            "uk_review_m0_assignment_revision",
            "idx_review_m0_assignment_current",
            "uk_review_m0_upload_input",
            "idx_review_m0_upload_current",
            "idx_review_m0_remote_preview_case",
            "uk_review_m0_remote_consent_job",
            "idx_review_m0_remote_consent_case",
            "idx_review_m0_diagnostic_case",
            "idx_review_m0_idempotency_response",
            "uk_review_m0_job_idempotency",
            "idx_review_m0_job_lease",
            "idx_review_m0_job_case",
            "idx_review_m0_job_owner",
            "uk_review_m0_remote_call_job",
            "uk_review_m0_report_job",
            "uk_review_m0_report_input",
            "idx_review_m0_report_freshness",
            "uk_review_m0_finding_stable",
            "idx_review_m0_finding_report",
            "idx_review_m0_evidence_finding",
            "uk_review_m0_decision_ignore",
            "idx_review_m0_decision_report");
    private static final Map<String, List<String>> REQUIRED_INDEX_COLUMNS = Map.ofEntries(
            Map.entry("idx_review_m0_case_owner_list", List.of("user_id", "updated_at", "case_id")),
            Map.entry("idx_review_m0_case_current_checklist", List.of("current_checklist_snapshot_id")),
            Map.entry("idx_review_m0_case_current_report", List.of("current_report_id")),
            Map.entry("uk_review_m0_basis_case_id", List.of("case_id", "basis_id")),
            Map.entry("idx_review_m0_basis_status_role", List.of("case_id", "status", "role")),
            Map.entry("idx_review_m0_requirement_case", List.of("case_id", "requirement_id")),
            Map.entry("uk_review_m0_checklist_revision_number", List.of("case_id", "revision_number")),
            Map.entry("idx_review_m0_checklist_revision_case", List.of("case_id", "lifecycle", "revision_number")),
            Map.entry("uk_review_m0_requirement_revision", List.of("requirement_id", "checklist_revision_id")),
            Map.entry("idx_review_m0_requirement_revision_checklist", List.of(
                    "checklist_revision_id", "enabled", "applicability")),
            Map.entry("uk_review_m0_checklist_snapshot_revision", List.of("case_id", "checklist_revision")),
            Map.entry("idx_review_m0_checklist_snapshot_lifecycle", List.of("case_id", "lifecycle")),
            Map.entry("idx_review_m0_submission_source_case", List.of("case_id", "status", "source_id")),
            Map.entry("uk_review_m0_material_top_level", List.of("top_level_source_id")),
            Map.entry("uk_review_m0_material_zip_entry", List.of("zip_entry_scope_digest")),
            Map.entry("idx_review_m0_material_case_status", List.of("case_id", "status", "material_id")),
            Map.entry("idx_review_m0_material_source", List.of("source_id", "material_id")),
            Map.entry("uk_review_m0_assignment_revision", List.of(
                    "case_id", "material_id", "requirement_id", "assignment_revision")),
            Map.entry("idx_review_m0_assignment_current", List.of(
                    "case_id", "assignment_revision", "status")),
            Map.entry("uk_review_m0_upload_input", List.of(
                    "case_id", "checklist_snapshot_id", "material_set_revision", "material_set_digest")),
            Map.entry("idx_review_m0_upload_current", List.of(
                    "case_id", "checklist_snapshot_id", "material_set_revision")),
            Map.entry("idx_review_m0_remote_preview_case", List.of("case_id", "basis_revision", "status")),
            Map.entry("uk_review_m0_remote_consent_job", List.of("consumed_by_job_id")),
            Map.entry("idx_review_m0_remote_consent_case", List.of(
                    "case_id", "basis_revision", "confirmed_at")),
            Map.entry("idx_review_m0_diagnostic_case", List.of("case_id", "subject_type", "created_at")),
            Map.entry("idx_review_m0_idempotency_response", List.of("owner_id", "response_type", "response_id")),
            Map.entry("uk_review_m0_job_idempotency", List.of(
                    "user_id", "case_id", "job_type", "idempotency_key")),
            Map.entry("idx_review_m0_job_lease", List.of("status", "lease_expires_at", "created_at")),
            Map.entry("idx_review_m0_job_case", List.of("case_id", "created_at")),
            Map.entry("idx_review_m0_job_owner", List.of("user_id", "case_id", "job_id")),
            Map.entry("uk_review_m0_remote_call_job", List.of("job_id")),
            Map.entry("uk_review_m0_report_job", List.of("job_id")),
            Map.entry("uk_review_m0_report_input", List.of("case_id", "input_fingerprint")),
            Map.entry("idx_review_m0_report_freshness", List.of("case_id", "freshness", "created_at")),
            Map.entry("uk_review_m0_finding_stable", List.of("report_id", "stable_finding_key")),
            Map.entry("idx_review_m0_finding_report", List.of("report_id", "finding_type", "finding_id")),
            Map.entry("idx_review_m0_evidence_finding", List.of("finding_id", "evidence_side", "evidence_id")),
            Map.entry("uk_review_m0_decision_ignore", List.of("finding_id", "decision_type")),
            Map.entry("idx_review_m0_decision_report", List.of("report_id", "decided_at")));
    private static final Map<String, List<String>> REQUIRED_PRIMARY_KEYS = requiredPrimaryKeys();
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
        validateVersionHistory();
        if (!versionInstalled(1)) {
            validateKnownPartialSchema();
            run(V1_RESOURCE);
            validateV1Shape();
            installVersion(1, "Review M0 core schema");
        } else {
            validateV1Shape();
        }
        if (!versionInstalled(2)) {
            installCyclicReferences();
            validateForeignKeysUnchecked();
            installVersion(2, "Review M0 cyclic references");
        } else {
            validateForeignKeysUnchecked();
        }
        if (!versionInstalled(3)) {
            run(V3_RESOURCE);
            validateV3Shape();
            installVersion(3, "Review M0 idempotency response snapshots");
        } else {
            validateV3Shape();
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
                throw invalidSchema("schema version must be exactly 3", null);
            }
            for (Map.Entry<String, String> entry : REQUIRED_COLUMNS.entrySet()) {
                jdbcTemplate.query("SELECT " + entry.getValue() + " FROM " + entry.getKey() + " WHERE 1 = 0",
                        resultSet -> { });
            }
            validateIndexes();
            validatePrimaryKeys(true);
            validateForeignKeys();
            validateV3Constraints();
        } catch (DataAccessException | SQLException error) {
            throw invalidSchema(
                    "a required table, column, index, or version is missing, or a required foreign key is missing",
                    error);
        }
    }

    private void validateV1Shape() {
        try {
            for (Map.Entry<String, String> entry : REQUIRED_COLUMNS.entrySet()) {
                if (entry.getKey().equals("review_m0_idempotency_response_snapshot")) {
                    continue;
                }
                jdbcTemplate.query("SELECT " + entry.getValue() + " FROM " + entry.getKey() + " WHERE 1 = 0",
                        resultSet -> { });
                validateExactColumns(entry.getKey(), entry.getValue());
            }
            validateIndexes();
            validatePrimaryKeys(false);
        } catch (DataAccessException | SQLException error) {
            throw invalidSchema("V001 is incomplete or has an unknown table shape", error);
        }
    }

    private void validateV3Shape() {
        try {
            Map.Entry<String, String> snapshot = Map.entry(
                    "review_m0_idempotency_response_snapshot",
                    REQUIRED_COLUMNS.get("review_m0_idempotency_response_snapshot"));
            jdbcTemplate.query("SELECT " + snapshot.getValue() + " FROM " + snapshot.getKey() + " WHERE 1 = 0",
                    resultSet -> { });
            validateExactColumns(snapshot.getKey(), snapshot.getValue());
            validatePrimaryKeys(true);
            validateV3Constraints();
        } catch (DataAccessException | SQLException error) {
            throw invalidSchema("V003 is incomplete or has an unknown table shape", error);
        }
    }

    private void validateV3Constraints() {
        Set<String> constraints = constraintNames();
        Set<String> required = Set.of(
                "ck_review_m0_idempotency_snapshot_format",
                "ck_review_m0_idempotency_snapshot_size");
        if (!constraints.containsAll(required)) {
            throw invalidSchema("required V003 CHECK constraints are missing", null);
        }
    }

    private void validateVersionHistory() {
        Set<Integer> versions;
        try {
            versions = new TreeSet<>(jdbcTemplate.query(
                    "SELECT version FROM review_m0_schema_version ORDER BY version",
                    (row, rowNumber) -> row.getInt(1)));
        } catch (DataAccessException missingVersionTable) {
            versions = Set.of();
        }
        if (!versions.equals(Set.of())
                && !versions.equals(Set.of(1))
                && !versions.equals(Set.of(1, 2))
                && !versions.equals(Set.of(1, 2, 3))) {
            throw invalidSchema("schema versions are unknown or non-contiguous: " + versions, null);
        }
    }

    private void validateKnownPartialSchema() {
        try {
            Set<String> actualTables = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            jdbcTemplate.query(
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE LOWER(table_schema)=LOWER(SCHEMA()) AND LOWER(table_name) LIKE 'review_m0_%'",
                    resultSet -> { actualTables.add(resultSet.getString(1)); });
            Set<String> unknown = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            unknown.addAll(actualTables);
            unknown.removeAll(REQUIRED_COLUMNS.keySet());
            if (!unknown.isEmpty()) {
                throw invalidSchema("partial V001 contains unknown Review M0 tables: " + unknown, null);
            }
            for (String table : actualTables) {
                validateExactColumns(table, REQUIRED_COLUMNS.get(table.toLowerCase(Locale.ROOT)));
            }
        } catch (DataAccessException error) {
            throw invalidSchema("partial V001 cannot be safely inspected", error);
        }
    }

    private void validateExactColumns(String table, String expectedColumns) {
        if (expectedColumns == null) {
            throw invalidSchema("no known shape exists for table " + table, null);
        }
        Set<String> expected = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        expected.addAll(List.of(expectedColumns.replace(" ", "").split(",")));
        Set<String> actual = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        jdbcTemplate.query(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE LOWER(table_schema)=LOWER(SCHEMA()) AND LOWER(table_name)=LOWER(?)",
                resultSet -> { actual.add(resultSet.getString(1)); }, table);
        if (!actual.equals(expected)) {
            throw invalidSchema("table " + table + " has an unknown column shape", null);
        }
    }

    private void validateForeignKeysUnchecked() {
        try {
            validateForeignKeys();
        } catch (SQLException error) {
            throw invalidSchema("required V002 foreign-key mappings or restrictive rules are missing", error);
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
        validateIndexColumns();
    }

    private void validateIndexColumns() throws SQLException {
        Map<String, SortedMap<Short, String>> actual = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (!metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")) {
                return;
            }
            for (String table : REQUIRED_COLUMNS.keySet()) {
                collectIndexColumns(metadata, table, actual);
                collectIndexColumns(metadata, table.toUpperCase(Locale.ROOT), actual);
            }
        }
        for (Map.Entry<String, List<String>> expected : REQUIRED_INDEX_COLUMNS.entrySet()) {
            SortedMap<Short, String> columns = actual.get(expected.getKey());
            List<String> ordered = columns == null ? List.of() : new java.util.ArrayList<>(columns.values());
            if (!ordered.equals(expected.getValue())) {
                throw invalidSchema("index " + expected.getKey() + " has an unknown column shape: " + ordered, null);
            }
        }
    }

    private static void collectIndexColumns(
            DatabaseMetaData metadata,
            String table,
            Map<String, SortedMap<Short, String>> indexes
    ) throws SQLException {
        try (ResultSet resultSet = metadata.getIndexInfo(null, null, table, false, false)) {
            while (resultSet.next()) {
                String name = resultSet.getString("INDEX_NAME");
                String column = normalized(resultSet.getString("COLUMN_NAME"));
                short position = resultSet.getShort("ORDINAL_POSITION");
                if (name != null && column != null && position > 0) {
                    indexes.computeIfAbsent(name, ignored -> new TreeMap<>()).put(position, column);
                }
            }
        }
    }

    private void validatePrimaryKeys(boolean includeV3) throws SQLException {
        Map<String, SortedMap<Short, String>> actual = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : REQUIRED_PRIMARY_KEYS.keySet()) {
                if (!includeV3 && table.equals("review_m0_idempotency_response_snapshot")) {
                    continue;
                }
                collectPrimaryKeys(metadata, table, actual);
                collectPrimaryKeys(metadata, table.toUpperCase(Locale.ROOT), actual);
            }
        }
        for (Map.Entry<String, List<String>> expected : REQUIRED_PRIMARY_KEYS.entrySet()) {
            if (!includeV3 && expected.getKey().equals("review_m0_idempotency_response_snapshot")) {
                continue;
            }
            SortedMap<Short, String> columns = actual.get(expected.getKey());
            List<String> ordered = columns == null ? List.of() : new java.util.ArrayList<>(columns.values());
            if (!ordered.equals(expected.getValue())) {
                throw invalidSchema("primary key for " + expected.getKey() + " has an unknown column shape", null);
            }
        }
    }

    private static void collectPrimaryKeys(
            DatabaseMetaData metadata,
            String table,
            Map<String, SortedMap<Short, String>> primaryKeys
    ) throws SQLException {
        try (ResultSet resultSet = metadata.getPrimaryKeys(null, null, table)) {
            while (resultSet.next()) {
                String actualTable = normalized(resultSet.getString("TABLE_NAME"));
                String column = normalized(resultSet.getString("COLUMN_NAME"));
                short position = resultSet.getShort("KEY_SEQ");
                if (actualTable != null && column != null) {
                    primaryKeys.computeIfAbsent(actualTable, ignored -> new TreeMap<>()).put(position, column);
                }
            }
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
        tables.put("review_m0_material", "material_id, case_id, source_id, kind, normalized_entry_path, zip_entry_scope_digest, original_entry_name, entry_index, content_digest, formal_file_type, submission_file_name, normalized_submission_file_name, status, cannot_confirm_reason, failure_stage, material_set_revision, name_revision, version, top_level_source_id, created_at, updated_at");
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
        tables.put("review_m0_idempotency", "scope_digest, owner_id, http_method, canonical_path, idempotency_key, request_digest, response_status, response_type, response_id, state, created_at, updated_at");
        tables.put("review_m0_idempotency_response_snapshot", "scope_digest, owner_id, http_method, canonical_path, idempotency_key, format_version, response_body, response_body_bytes, snapshot_digest, created_at");
        return Map.copyOf(tables);
    }

    private static Map<String, List<String>> requiredPrimaryKeys() {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        keys.put("review_m0_schema_version", List.of("version"));
        keys.put("review_m0_case", List.of("case_id"));
        keys.put("review_m0_official_basis", List.of("basis_id"));
        keys.put("review_m0_requirement", List.of("requirement_id"));
        keys.put("review_m0_checklist_revision", List.of("checklist_revision_id"));
        keys.put("review_m0_requirement_revision", List.of("requirement_revision_id"));
        keys.put("review_m0_checklist_snapshot", List.of("checklist_snapshot_id"));
        keys.put("review_m0_submission_source", List.of("source_id"));
        keys.put("review_m0_material", List.of("material_id"));
        keys.put("review_m0_material_assignment", List.of("assignment_id"));
        keys.put("review_m0_upload_confirmation", List.of("upload_confirmation_id"));
        keys.put("review_m0_remote_preview", List.of("preview_id"));
        keys.put("review_m0_remote_consent", List.of("consent_id"));
        keys.put("review_m0_input_diagnostic", List.of("diagnostic_id"));
        keys.put("review_m0_idempotency", List.of("scope_digest"));
        keys.put("review_m0_job", List.of("job_id"));
        keys.put("review_m0_job_checkpoint", List.of("job_id", "checkpoint_key"));
        keys.put("review_m0_remote_call", List.of("remote_call_id"));
        keys.put("review_m0_report", List.of("report_id"));
        keys.put("review_m0_finding", List.of("finding_id"));
        keys.put("review_m0_evidence_ref", List.of("evidence_id"));
        keys.put("review_m0_finding_decision", List.of("decision_id"));
        keys.put("review_m0_idempotency_response_snapshot", List.of("scope_digest"));
        return Map.copyOf(keys);
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
