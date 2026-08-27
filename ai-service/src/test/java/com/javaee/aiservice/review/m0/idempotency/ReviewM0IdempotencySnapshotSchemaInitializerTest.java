package com.javaee.aiservice.review.m0.idempotency;

import com.javaee.aiservice.review.m0.store.ReviewM0PersistenceProperties;
import com.javaee.aiservice.review.m0.store.ReviewM0SchemaInitializer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewM0IdempotencySnapshotSchemaInitializerTest {
    @Test
    void additiveV3MigrationIsIdempotentAndRecordsTheValidatedVersion() {
        JdbcDataSource dataSource = dataSource();
        ReviewM0PersistenceProperties properties = properties(true);
        new ReviewM0SchemaInitializer(dataSource, properties).afterPropertiesSet();
        ReviewM0IdempotencySnapshotSchemaInitializer initializer =
                new ReviewM0IdempotencySnapshotSchemaInitializer(dataSource, properties);

        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE LOWER(table_name)='review_m0_idempotency_response_snapshot'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_schema_version", Integer.class)).isEqualTo(3);
    }

    @Test
    void enabledManualMigrationModeFailsClosedWhenV3IsMissing() {
        JdbcDataSource dataSource = dataSource();
        ReviewM0PersistenceProperties core = properties(true);
        new ReviewM0SchemaInitializer(dataSource, core).afterPropertiesSet();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE review_m0_idempotency_response_snapshot");
        jdbc.update("DELETE FROM review_m0_schema_version WHERE version=3");

        assertThatThrownBy(() -> new ReviewM0IdempotencySnapshotSchemaInitializer(
                dataSource, properties(false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V003__review_m0_idempotency_response_snapshot.sql");
    }

    private static ReviewM0PersistenceProperties properties(boolean autoMigration) {
        ReviewM0PersistenceProperties properties = new ReviewM0PersistenceProperties();
        properties.setEnabled(true);
        properties.getSchema().getAutoMigration().setEnabled(autoMigration);
        return properties;
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:idempotency_schema_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }
}
