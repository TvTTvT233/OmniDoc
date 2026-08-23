package com.javaee.aiservice.review.m0.idempotency;

import com.javaee.aiservice.review.m0.store.ReviewM0PersistenceProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Additive compatibility migration kept outside the frozen V001/V002 core initializer. */
@Component
@DependsOn("reviewM0SchemaInitializer")
public final class ReviewM0IdempotencySnapshotSchemaInitializer implements InitializingBean {
    public static final String V3_RESOURCE =
            "db/review-m0/V003__review_m0_idempotency_response_snapshot.sql";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final ReviewM0PersistenceProperties properties;

    public ReviewM0IdempotencySnapshotSchemaInitializer(
            DataSource dataSource,
            ReviewM0PersistenceProperties properties
    ) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getSchema().getAutoMigration().isEnabled()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource(V3_RESOURCE));
            populator.setContinueOnError(false);
            populator.execute(dataSource);
        }
        try {
            jdbc.query("""
                    SELECT scope_digest, owner_id, http_method, canonical_path, idempotency_key,
                           format_version, response_body, response_body_bytes, snapshot_digest, created_at
                    FROM review_m0_idempotency_response_snapshot WHERE 1 = 0
                    """, resultSet -> { });
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "Review M0 idempotency snapshots require " + V3_RESOURCE, error);
        }
    }
}
