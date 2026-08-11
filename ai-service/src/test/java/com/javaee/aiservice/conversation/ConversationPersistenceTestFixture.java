package com.javaee.aiservice.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.conversation.config.MemoryPersistenceProperties;
import com.javaee.aiservice.conversation.runtime.RuntimeStateCleanup;
import com.javaee.aiservice.conversation.service.ConversationService;
import com.javaee.aiservice.conversation.service.LegacyConversationImporter;
import com.javaee.aiservice.conversation.service.LegacyConversationSource;
import com.javaee.aiservice.conversation.store.JdbcConversationStore;
import com.javaee.aiservice.conversation.store.MemorySchemaInitializer;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

public final class ConversationPersistenceTestFixture {

    private static final RuntimeStateCleanup NO_RUNTIME_STATE = conversationId -> {
    };

    private final JdbcDataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryPersistenceProperties properties = new MemoryPersistenceProperties();

    public ConversationPersistenceTestFixture() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:m02_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        properties.setEnabled(true);
        properties.getSchema().getAutoMigration().setEnabled(true);
        new MemorySchemaInitializer(dataSource, properties).afterPropertiesSet();
    }

    public ConversationService newService() {
        return newService((conversationId, userId) -> Optional.empty(), NO_RUNTIME_STATE);
    }

    public ConversationService newService(LegacyConversationSource legacySource) {
        return newService(legacySource, NO_RUNTIME_STATE);
    }

    public ConversationService newService(RuntimeStateCleanup runtimeStateCleanup) {
        return newService((conversationId, userId) -> Optional.empty(), runtimeStateCleanup);
    }

    public ConversationService newService(
            LegacyConversationSource legacySource,
            RuntimeStateCleanup runtimeStateCleanup
    ) {
        JdbcConversationStore store = newStore();
        LegacyConversationImporter importer = new LegacyConversationImporter(store, legacySource, properties);
        return new ConversationService(store, importer, runtimeStateCleanup, properties);
    }

    public JdbcConversationStore newStore() {
        return new JdbcConversationStore(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource),
                objectMapper);
    }

    public JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public MemoryPersistenceProperties properties() {
        return properties;
    }
}
