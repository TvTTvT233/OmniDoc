package com.javaee.aiservice.review.m0.store;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.javaee.aiservice.review.m0.model.ReviewM0KeyDigests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewM0SchemaInitializerTest {
    @Test
    void initializesAllFrozenTablesAndRepeatedInitializationIsIdempotent() {
        JdbcDataSource dataSource = dataSource();
        ReviewM0PersistenceProperties properties = enabled(true);
        ReviewM0SchemaInitializer initializer = new ReviewM0SchemaInitializer(dataSource, properties);

        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_m0_schema_version", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME LIKE 'review_m0_%'", Integer.class)).isEqualTo(23);
    }

    @Test
    void validationDetectsMissingTableColumnAndWrongVersion() {
        JdbcDataSource dataSource = dataSource();
        ReviewM0SchemaInitializer initializer = new ReviewM0SchemaInitializer(dataSource, enabled(true));
        initializer.afterPropertiesSet();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("ALTER TABLE review_m0_official_basis DROP COLUMN failure_stage");
        assertThatThrownBy(initializer::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("table, column, index, or version");

        JdbcDataSource wrongVersion = dataSource();
        ReviewM0SchemaInitializer wrongInitializer = new ReviewM0SchemaInitializer(wrongVersion, enabled(true));
        wrongInitializer.afterPropertiesSet();
        new JdbcTemplate(wrongVersion).update(
                "INSERT INTO review_m0_schema_version(version, description, installed_at) VALUES (4, 'unexpected', CURRENT_TIMESTAMP)");
        assertThatThrownBy(wrongInitializer::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema version must be exactly 3");

        JdbcDataSource unexpectedLowerVersion = dataSource();
        ReviewM0SchemaInitializer lowerInitializer = new ReviewM0SchemaInitializer(unexpectedLowerVersion, enabled(true));
        lowerInitializer.afterPropertiesSet();
        new JdbcTemplate(unexpectedLowerVersion).update(
                "INSERT INTO review_m0_schema_version(version, description, installed_at) VALUES (0, 'unexpected', CURRENT_TIMESTAMP)");
        assertThatThrownBy(lowerInitializer::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema version must be exactly 3");
    }

    @Test
    void resumesVersionTwoAfterAInterruptedCyclicReferenceMigration() {
        JdbcDataSource dataSource = dataSource();
        ReviewM0SchemaInitializer initializer = new ReviewM0SchemaInitializer(dataSource, enabled(true));
        initializer.afterPropertiesSet();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.update("DELETE FROM review_m0_schema_version WHERE version IN (2, 3)");
        jdbc.execute("DROP TABLE review_m0_idempotency_response_snapshot");
        jdbc.execute("ALTER TABLE review_m0_remote_consent DROP CONSTRAINT fk_review_m0_remote_consent_job");

        initializer.migrate();
        initializer.validate();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE LOWER(constraint_name) = 'fk_review_m0_remote_consent_job'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_schema_version WHERE version = 2",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void resumesTheKnownEightOfTwentyTwoMysqlAutoCommitPartialSchema() throws Exception {
        JdbcDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String script = Files.readString(
                new ClassPathResource(ReviewM0SchemaInitializer.V1_RESOURCE).getFile().toPath(),
                StandardCharsets.UTF_8);
        String[] statements = Arrays.stream(script.split(";"))
                .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
        assertThat(statements).hasSize(22);
        for (int index = 0; index < 8; index++) {
            jdbc.execute(statements[index]);
        }
        assertThat(reviewTableCount(jdbc)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_schema_version", Integer.class)).isZero();

        ReviewM0SchemaInitializer initializer = new ReviewM0SchemaInitializer(dataSource, enabled(true));
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();

        assertThat(reviewTableCount(jdbc)).isEqualTo(23);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_schema_version", Integer.class)).isEqualTo(3);
    }

    @Test
    void upgradesValidatedV1V2ToV3WithoutChangingExistingData() {
        JdbcDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        run(dataSource, ReviewM0SchemaInitializer.V1_RESOURCE);
        jdbc.update("INSERT INTO review_m0_schema_version VALUES (1, 'v1', ?)",
                Timestamp.from(Instant.now()));
        run(dataSource, ReviewM0SchemaInitializer.V2_RESOURCE);
        jdbc.update("INSERT INTO review_m0_schema_version VALUES (2, 'v2', ?)",
                Timestamp.from(Instant.now()));
        String digest = ReviewM0KeyDigests.idempotencyScope(
                "owner", "POST", "/" + "路".repeat(500), "键".repeat(200));
        jdbc.update("""
                        INSERT INTO review_m0_idempotency(
                            scope_digest, owner_id, http_method, canonical_path, idempotency_key,
                            request_digest, response_status, response_type, response_id, state,
                            created_at, updated_at)
                        VALUES (?, 'owner', 'POST', ?, ?, ?, 201, 'CASE', 'case-1', 'COMPLETED', ?, ?)
                        """, digest, "/" + "路".repeat(500), "键".repeat(200), "a".repeat(64),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

        new ReviewM0SchemaInitializer(dataSource, enabled(true)).afterPropertiesSet();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_schema_version", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT request_digest FROM review_m0_idempotency WHERE scope_digest=?",
                String.class, digest)).isEqualTo("a".repeat(64));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot", Integer.class)).isZero();
    }

    @Test
    void refusesUnknownPartialTableShapeWithoutDroppingOrVersioningIt() {
        JdbcDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE review_m0_schema_version(version INT PRIMARY KEY, unknown_data VARCHAR(32))");

        assertThatThrownBy(() -> new ReviewM0SchemaInitializer(dataSource, enabled(true)).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown column shape");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_schema_version", Integer.class)).isZero();
        assertThat(reviewTableCount(jdbc)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fk_review_m0_case_current_checklist",
            "fk_review_m0_case_current_report",
            "fk_review_m0_checklist_revision_job",
            "fk_review_m0_remote_consent_job"
    })
    void strictValidationRejectsEveryMissingV002ForeignKey(String constraintName) {
        JdbcDataSource dataSource = dataSource();
        ReviewM0SchemaInitializer initializer = new ReviewM0SchemaInitializer(dataSource, enabled(true));
        initializer.afterPropertiesSet();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String table = switch (constraintName) {
            case "fk_review_m0_case_current_checklist", "fk_review_m0_case_current_report" -> "review_m0_case";
            case "fk_review_m0_checklist_revision_job" -> "review_m0_checklist_revision";
            case "fk_review_m0_remote_consent_job" -> "review_m0_remote_consent";
            default -> throw new AssertionError("Unexpected fixture constraint " + constraintName);
        };
        jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraintName);

        assertThatThrownBy(initializer::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V002 foreign-key mappings");
    }

    @Test
    void strictValidationUsesForeignKeyStructureRatherThanOnlyConstraintName() {
        JdbcDataSource dataSource = dataSource();
        ReviewM0SchemaInitializer initializer = new ReviewM0SchemaInitializer(dataSource, enabled(true));
        initializer.afterPropertiesSet();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE review_m0_remote_consent "
                + "DROP CONSTRAINT fk_review_m0_remote_consent_job");
        jdbc.execute("ALTER TABLE review_m0_remote_consent "
                + "ADD CONSTRAINT fk_review_m0_remote_consent_job "
                + "FOREIGN KEY (consumed_by_job_id) REFERENCES review_m0_case(case_id)");

        assertThatThrownBy(initializer::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V002 foreign-key mappings");
    }

    @Test
    void strictValidationRejectsCascadingRulesOnV002ForeignKeys() {
        JdbcDataSource dataSource = dataSource();
        ReviewM0SchemaInitializer initializer = new ReviewM0SchemaInitializer(dataSource, enabled(true));
        initializer.afterPropertiesSet();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE review_m0_remote_consent "
                + "DROP CONSTRAINT fk_review_m0_remote_consent_job");
        jdbc.execute("ALTER TABLE review_m0_remote_consent "
                + "ADD CONSTRAINT fk_review_m0_remote_consent_job "
                + "FOREIGN KEY (consumed_by_job_id) REFERENCES review_m0_job(job_id) ON DELETE CASCADE");

        assertThatThrownBy(initializer::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restrictive rules");
    }

    @Test
    void enabledWithoutAutoMigrationFailsAndDisabledDoesNotOpenDataSource() {
        assertThatThrownBy(() -> new ReviewM0SchemaInitializer(dataSource(), enabled(false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Review M0 persistence is enabled");

        AbstractDataSource failOnConnect = new AbstractDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError("disabled Review M0 persistence opened a connection");
            }

            @Override
            public Connection getConnection(String username, String password) {
                throw new AssertionError("disabled Review M0 persistence opened a connection");
            }
        };
        new ReviewM0SchemaInitializer(failOnConnect, new ReviewM0PersistenceProperties()).afterPropertiesSet();
    }

    private static ReviewM0PersistenceProperties enabled(boolean autoMigration) {
        ReviewM0PersistenceProperties properties = new ReviewM0PersistenceProperties();
        properties.setEnabled(true);
        properties.getSchema().getAutoMigration().setEnabled(autoMigration);
        return properties;
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:schema_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private static void run(JdbcDataSource dataSource, String resource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(resource));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }

    private static int reviewTableCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE LOWER(table_name) LIKE 'review_m0_%'", Integer.class);
    }
}
