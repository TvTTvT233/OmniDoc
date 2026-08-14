package com.javaee.aiservice.review.m0.store;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.util.UUID;

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
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME LIKE 'review_m0_%'", Integer.class)).isEqualTo(22);
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
                "INSERT INTO review_m0_schema_version(version, description, installed_at) VALUES (3, 'unexpected', CURRENT_TIMESTAMP)");
        assertThatThrownBy(wrongInitializer::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema version must be exactly 2");

        JdbcDataSource unexpectedLowerVersion = dataSource();
        ReviewM0SchemaInitializer lowerInitializer = new ReviewM0SchemaInitializer(unexpectedLowerVersion, enabled(true));
        lowerInitializer.afterPropertiesSet();
        new JdbcTemplate(unexpectedLowerVersion).update(
                "INSERT INTO review_m0_schema_version(version, description, installed_at) VALUES (0, 'unexpected', CURRENT_TIMESTAMP)");
        assertThatThrownBy(lowerInitializer::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema version must be exactly 2");
    }

    @Test
    void resumesVersionTwoAfterAInterruptedCyclicReferenceMigration() {
        JdbcDataSource dataSource = dataSource();
        ReviewM0SchemaInitializer initializer = new ReviewM0SchemaInitializer(dataSource, enabled(true));
        initializer.afterPropertiesSet();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.update("DELETE FROM review_m0_schema_version WHERE version = 2");
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
}
