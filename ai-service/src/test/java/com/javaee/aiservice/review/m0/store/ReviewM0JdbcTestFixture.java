package com.javaee.aiservice.review.m0.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.idempotency.ReviewM0IdempotencySnapshotSchemaInitializer;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.util.UUID;

public final class ReviewM0JdbcTestFixture {
    private final JdbcDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final DataSourceTransactionManager transactionManager;

    public ReviewM0JdbcTestFixture() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:review_m0_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        ReviewM0PersistenceProperties properties = new ReviewM0PersistenceProperties();
        properties.setEnabled(true);
        properties.getSchema().getAutoMigration().setEnabled(true);
        new ReviewM0SchemaInitializer(dataSource, properties).afterPropertiesSet();
        new ReviewM0IdempotencySnapshotSchemaInitializer(dataSource, properties).afterPropertiesSet();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    public DataSourceTransactionManager transactions() {
        return transactionManager;
    }

    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
