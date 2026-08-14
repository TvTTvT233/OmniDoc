package com.javaee.aiservice.review.m0.store;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;

import static com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewM0DdlConstraintTest {
    @Test
    void frozenForeignKeysAndRequirementChecksRejectInvalidRows() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-ddl", "owner");

        assertThatThrownBy(() -> fixture.jdbc().update("""
                        INSERT INTO review_m0_requirement(requirement_id, case_id, origin, created_at)
                        VALUES ('orphan', 'missing-case', 'USER_ADDED', ?)
                        """, Timestamp.from(T0))).isInstanceOf(DataIntegrityViolationException.class);

        fixture.jdbc().update("""
                        INSERT INTO review_m0_requirement(requirement_id, case_id, origin, created_at)
                        VALUES ('req', 'case-ddl', 'USER_ADDED', ?)
                        """, Timestamp.from(T0));
        fixture.jdbc().update("""
                        INSERT INTO review_m0_checklist_revision(
                            checklist_revision_id, case_id, lifecycle, basis_revision, revision_number,
                            draft_digest, extraction_job_id, created_by, created_at
                        ) VALUES ('clrev', 'case-ddl', 'CANDIDATE_DRAFT', 0, 1, ?, NULL, 'owner', ?)
                        """, "a".repeat(64), Timestamp.from(T0));
        assertThatThrownBy(() -> fixture.jdbc().update("""
                        INSERT INTO review_m0_requirement_revision(
                            requirement_revision_id, requirement_id, checklist_revision_id, display_name,
                            enabled, applicability, minimum_count, file_name_rule_kind,
                            file_name_rule_pattern, file_name_rule_case_sensitive, authorship,
                            source_refs_json, item_version, created_at
                        ) VALUES ('reqrev', 'req', 'clrev', 'Synthetic', TRUE, 'APPLIES', 0,
                                  'NONE', NULL, NULL, 'USER_REWRITTEN', '[]', 0, ?)
                        """, Timestamp.from(T0))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ordinarySourceHasOneTopLevelMaterialWhileZipAllowsDistinctLeavesOnly() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-material", "owner");
        insertSource(fixture, "source-file", "PDF");
        insertMaterial(fixture, "mat-file", "source-file", "TOP_LEVEL_FILE", null, "PDF", "form.pdf");
        assertThatThrownBy(() -> insertMaterial(
                fixture, "mat-file-duplicate", "source-file", "TOP_LEVEL_FILE", null, "PDF", "other.pdf"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertSource(fixture, "source-zip", "ZIP");
        insertMaterial(fixture, "mat-a", "source-zip", "ZIP_ENTRY", "a/form.pdf", "PDF", "form.pdf");
        insertMaterial(fixture, "mat-b", "source-zip", "ZIP_ENTRY", "b/proof.png", "PNG", "proof.png");
        assertThatThrownBy(() -> insertMaterial(
                fixture, "mat-duplicate-path", "source-zip", "ZIP_ENTRY", "a/form.pdf", "PDF", "copy.pdf"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_material WHERE source_id = 'source-zip'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void zipSourceIsSupportedButDatabaseRejectsTheContainerAsAMaterialUnit() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-material", "owner");
        insertSource(fixture, "source-zip-container", "ZIP");

        assertThat(fixture.jdbc().queryForObject(
                "SELECT formal_file_type FROM review_m0_submission_source WHERE source_id = 'source-zip-container'",
                String.class)).isEqualTo("ZIP");
        assertThatThrownBy(() -> insertMaterial(
                fixture, "mat-zip-container", "source-zip-container", "TOP_LEVEL_FILE", null,
                "ZIP", "submission.zip"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_material WHERE source_id = 'source-zip-container'",
                Integer.class)).isZero();
    }

    @Test
    void schemaContainsNamedUniqueAndForeignKeyConstraints() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        Integer foreignKeys = fixture.jdbc().queryForObject("""
                        SELECT COUNT(*) FROM information_schema.table_constraints
                        WHERE LOWER(table_name) LIKE 'review_m0_%' AND constraint_type = 'FOREIGN KEY'
                        """, Integer.class);
        Integer uniques = fixture.jdbc().queryForObject("""
                        SELECT COUNT(*) FROM information_schema.table_constraints
                        WHERE LOWER(table_name) LIKE 'review_m0_%' AND constraint_type IN ('UNIQUE', 'PRIMARY KEY')
                        """, Integer.class);
        assertThat(foreignKeys).isGreaterThanOrEqualTo(30);
        assertThat(uniques).isGreaterThanOrEqualTo(30);
    }

    private static void insertSource(ReviewM0JdbcTestFixture fixture, String sourceId, String type) {
        fixture.jdbc().update("""
                        INSERT INTO review_m0_submission_source(
                            source_id, case_id, file_id, original_file_name_snapshot, declared_content_type,
                            formal_file_type, status, file_size_bytes, file_digest, storage_version,
                            attached_material_set_revision, removed_material_set_revision, version, created_at, updated_at
                        ) VALUES (?, 'case-material', ?, 'synthetic', NULL, ?, 'ATTACHED', 1, ?, NULL, 1, NULL, 0, ?, ?)
                        """,
                sourceId, "file-" + sourceId, type, "a".repeat(64), Timestamp.from(T0), Timestamp.from(T0));
    }

    private static void insertMaterial(
            ReviewM0JdbcTestFixture fixture,
            String materialId,
            String sourceId,
            String kind,
            String entryPath,
            String type,
            String name
    ) {
        fixture.jdbc().update("""
                        INSERT INTO review_m0_material(
                            material_id, case_id, source_id, kind, normalized_entry_path, original_entry_name,
                            entry_index, content_digest, formal_file_type, submission_file_name,
                            normalized_submission_file_name, status, cannot_confirm_reason, failure_stage,
                            material_set_revision, name_revision, version, created_at, updated_at
                        ) VALUES (?, 'case-material', ?, ?, ?, ?, 0, ?, ?, ?, ?, 'AVAILABLE', NULL, NULL, 1, 0, 0, ?, ?)
                        """,
                materialId, sourceId, kind, entryPath, entryPath, "b".repeat(64), type, name, name,
                Timestamp.from(T0), Timestamp.from(T0));
    }
}
