package com.javaee.aiservice.review.m0.baseline;

import com.javaee.aiservice.conversation.config.MemoryPersistenceProperties;
import com.javaee.aiservice.conversation.store.MemorySchemaInitializer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentJdbcMigrationBaselineTest {

    @Test
    void currentInitializerRunsBothVersionedScriptsInH2MysqlModeAndIsIdempotent() throws Exception {
        JdbcDataSource dataSource = mysqlModeDataSource("baseline_");
        MemoryPersistenceProperties properties = enabledProperties(true);
        MemorySchemaInitializer initializer = new MemorySchemaInitializer(dataSource, properties);

        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_memory_schema_version WHERE version IN (1, 2)",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME IN ('ai_conversation', 'ai_conversation_message', "
                        + "'ai_conversation_summary')",
                Integer.class)).isEqualTo(3);
        assertThat(dataSource.getURL()).contains("MODE=MySQL");
    }

    @Test
    void enabledPersistenceValidatesAndFailsWhenMigrationIsNotExplicitlyEnabled() {
        JdbcDataSource dataSource = mysqlModeDataSource("missing_");

        assertThatThrownBy(() -> new MemorySchemaInitializer(
                dataSource,
                enabledProperties(false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema version 2 is missing")
                .hasMessageContaining("ai.memory.schema.auto-migration.enabled");
    }

    @Test
    void disabledPersistenceDoesNotOpenTheConfiguredDataSource() {
        AbstractDataSource failOnConnect = new AbstractDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError("disabled persistence opened a database connection");
            }

            @Override
            public Connection getConnection(String username, String password) {
                throw new AssertionError("disabled persistence opened a database connection");
            }
        };

        new MemorySchemaInitializer(
                failOnConnect,
                new MemoryPersistenceProperties()).afterPropertiesSet();
    }

    @Test
    void applicationConfigurationUsesMysqlAndKeepsAutomaticMigrationOffByDefault() {
        String application = RepositorySourceEvidence.read("ai-service/src/main/resources/application.yml");
        String local = RepositorySourceEvidence.read("ai-service/src/main/resources/application-local.yml");
        String initializer = RepositorySourceEvidence.read(
                "ai-service/src/main/java/com/javaee/aiservice/conversation/store/MemorySchemaInitializer.java");

        assertThat(application)
                .contains("url: jdbc:mysql://")
                .contains("driver-class-name: com.mysql.cj.jdbc.Driver")
                .contains("enabled: ${AI_MEMORY_SCHEMA_AUTO_MIGRATION_ENABLED:false}");
        assertThat(local)
                .contains("url: jdbc:mysql://")
                .contains("enabled: ${AI_MEMORY_SCHEMA_AUTO_MIGRATION_ENABLED:false}");
        assertThat(initializer)
                .contains("implements InitializingBean")
                .contains("db/memory/V001__conversation_message.sql")
                .contains("db/memory/V002__conversation_summary.sql")
                .contains("if (properties.getSchema().getAutoMigration().isEnabled())")
                .contains("validate()");
    }

    private static JdbcDataSource mysqlModeDataSource(String prefix) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + prefix + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private static MemoryPersistenceProperties enabledProperties(boolean autoMigration) {
        MemoryPersistenceProperties properties = new MemoryPersistenceProperties();
        properties.setEnabled(true);
        properties.getSchema().getAutoMigration().setEnabled(autoMigration);
        return properties;
    }
}
