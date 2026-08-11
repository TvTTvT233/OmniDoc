package com.javaee.aiservice.conversation.store;

import com.javaee.aiservice.conversation.config.MemoryPersistenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

@Component
public class MemorySchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MemorySchemaInitializer.class);
    private static final int CONVERSATION_SCHEMA_VERSION = 2;
    private static final String V1_MIGRATION_RESOURCE = "db/memory/V001__conversation_message.sql";
    private static final String V2_MIGRATION_RESOURCE = "db/memory/V002__conversation_summary.sql";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final MemoryPersistenceProperties properties;

    public MemorySchemaInitializer(DataSource dataSource, MemoryPersistenceProperties properties) {
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
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource(V1_MIGRATION_RESOURCE),
                new ClassPathResource(V2_MIGRATION_RESOURCE));
        populator.setContinueOnError(false);
        populator.execute(dataSource);

        jdbcTemplate.update(
                "INSERT IGNORE INTO ai_memory_schema_version(version, description, installed_at) VALUES (?, ?, ?)",
                1,
                "Conversation and structured Message persistence",
                Timestamp.from(Instant.now()));
        jdbcTemplate.update(
                "INSERT IGNORE INTO ai_memory_schema_version(version, description, installed_at) VALUES (?, ?, ?)",
                CONVERSATION_SCHEMA_VERSION,
                "Structured rolling Conversation Summary persistence",
                Timestamp.from(Instant.now()));
        log.info("Memory schema is ready at version {}", CONVERSATION_SCHEMA_VERSION);
    }

    public void validate() {
        try {
            Integer installed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_memory_schema_version WHERE version = ?",
                    Integer.class,
                    CONVERSATION_SCHEMA_VERSION);
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE 1 = 0", Integer.class);
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_conversation_message WHERE 1 = 0", Integer.class);
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_conversation_summary WHERE 1 = 0", Integer.class);
            if (installed == null || installed != 1) {
                throw missingSchema(null);
            }
        } catch (DataAccessException error) {
            throw missingSchema(error);
        }
    }

    private IllegalStateException missingSchema(Throwable cause) {
        return new IllegalStateException(
                "Memory persistence is enabled but schema version 2 is missing. "
                        + "Apply " + V1_MIGRATION_RESOURCE + " and " + V2_MIGRATION_RESOURCE
                        + " or explicitly enable "
                        + "ai.memory.schema.auto-migration.enabled.",
                cause);
    }
}
