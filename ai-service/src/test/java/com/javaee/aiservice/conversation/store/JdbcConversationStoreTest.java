package com.javaee.aiservice.conversation.store;

import com.javaee.aiservice.conversation.ConversationPersistenceTestFixture;
import com.javaee.aiservice.conversation.config.MemoryPersistenceProperties;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessagePage;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.model.ConversationStatus;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import com.javaee.aiservice.conversation.service.ConversationService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcConversationStoreTest {

    @Test
    void schemaMigrationIsVersionedAndIdempotent() {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();

        new MemorySchemaInitializer(fixture.dataSource(), fixture.properties()).afterPropertiesSet();

        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_memory_schema_version WHERE version = 1", Integer.class))
                .isEqualTo(1);
        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME IN ('ai_conversation', 'ai_conversation_message')",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void enabledPersistenceFailsClearlyWhenMigrationIsDisabledAndSchemaIsMissing() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:missing_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1");
        MemoryPersistenceProperties properties = new MemoryPersistenceProperties();
        properties.setEnabled(true);

        assertThatThrownBy(() -> new MemorySchemaInitializer(dataSource, properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema version 1 is missing")
                .hasMessageContaining("auto-migration.enabled");
    }

    @Test
    void disabledPersistenceDoesNotOpenADatabaseConnectionInOrdinaryUnitContexts() {
        MemoryPersistenceProperties properties = new MemoryPersistenceProperties();
        AbstractDataSource failOnConnect = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new AssertionError("disabled Memory must not connect to an external database");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new AssertionError("disabled Memory must not connect to an external database");
            }
        };

        new MemorySchemaInitializer(failOnConnect, properties).afterPropertiesSet();
    }

    @Test
    void concurrentAppendsAllocateUniqueContinuousSequencesInOneDatabase() throws Exception {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();
        ConversationService service = fixture.newService();
        String conversationId = service.createConversation("owner").conversationId();
        int appendCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < appendCount; index++) {
                int messageNumber = index;
                futures.add(executor.submit(() -> service.appendMessages(
                        conversationId,
                        "owner",
                        List.of(MessageAppendCommand.text(
                                "turn-" + messageNumber,
                                "request-" + messageNumber,
                                0,
                                ConversationRole.USER,
                                "message-" + messageNumber)))));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        List<ConversationMessage> messages = service.listMessages(
                conversationId, "owner", null, 100).items();
        assertThat(messages).hasSize(appendCount);
        assertThat(messages).extracting(ConversationMessage::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, appendCount).boxed().toList());
        assertThat(fixture.jdbcTemplate().queryForObject("""
                        SELECT COUNT(*) FROM (
                            SELECT conversation_id, `sequence`, COUNT(*) AS duplicates
                            FROM ai_conversation_message
                            GROUP BY conversation_id, `sequence`
                            HAVING COUNT(*) > 1
                        ) duplicate_sequences
                        """, Integer.class)).isZero();
    }

    @Test
    void paginationArchiveRestoreAndPhysicalDeleteFollowTheLifecycleContract() {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();
        ConversationService service = fixture.newService();
        String conversationId = service.createConversation("owner").conversationId();
        for (int index = 1; index <= 5; index++) {
            service.appendMessages(conversationId, "owner", List.of(MessageAppendCommand.text(
                    "turn-" + index,
                    "request-" + index,
                    0,
                    ConversationRole.USER,
                    "message-" + index)));
        }

        ConversationMessagePage newest = service.listMessages(conversationId, "owner", null, 2);
        assertThat(newest.items()).extracting(ConversationMessage::sequence).containsExactly(4L, 5L);
        assertThat(newest.nextBeforeSequence()).isEqualTo(4L);
        assertThat(service.listMessages(conversationId, "owner", newest.nextBeforeSequence(), 2).items())
                .extracting(ConversationMessage::sequence).containsExactly(2L, 3L);

        assertThat(service.archive(conversationId, "owner").status())
                .isEqualTo(ConversationStatus.ARCHIVED);
        assertThatThrownBy(() -> service.appendMessages(
                conversationId,
                "owner",
                List.of(MessageAppendCommand.text("turn-x", "request-x", 0,
                        ConversationRole.USER, "blocked"))))
                .isInstanceOf(ConversationMemoryException.class)
                .satisfies(error -> assertThat(((ConversationMemoryException) error).getCode())
                        .isEqualTo(ConversationErrorCode.CONVERSATION_ARCHIVED));
        assertThat(service.restore(conversationId, "owner").status()).isEqualTo(ConversationStatus.ACTIVE);

        service.delete(conversationId, "owner");
        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = ?",
                Integer.class,
                conversationId)).isZero();
        assertThatThrownBy(() -> service.requireOwned(conversationId, "owner"))
                .isInstanceOf(ConversationMemoryException.class)
                .satisfies(error -> assertThat(((ConversationMemoryException) error).getCode())
                        .isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
    }

    @Test
    void conversationListUsesStableCursorAndOwnerStatusFilters() {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();
        ConversationService service = fixture.newService();
        String first = service.createConversation("owner").conversationId();
        String second = service.createConversation("owner").conversationId();
        String third = service.createConversation("owner").conversationId();
        service.createConversation("other");

        var pageOne = service.listConversations("owner", ConversationStatus.ACTIVE, null, 2);
        var pageTwo = service.listConversations(
                "owner", ConversationStatus.ACTIVE, pageOne.nextCursor(), 2);

        assertThat(pageOne.items()).hasSize(2);
        assertThat(pageOne.nextCursor()).isNotNull();
        assertThat(pageTwo.items()).hasSize(1);
        assertThat(java.util.stream.Stream.concat(pageOne.items().stream(), pageTwo.items().stream())
                .map(conversation -> conversation.conversationId()).toList())
                .containsExactlyInAnyOrder(first, second, third);

        service.archive(first, "owner");
        assertThat(service.listConversations("owner", ConversationStatus.ARCHIVED, null, 10).items())
                .extracting(conversation -> conversation.conversationId())
                .containsExactly(first);
        assertThat(service.listConversations("other", ConversationStatus.ACTIVE, null, 10).items())
                .hasSize(1)
                .allSatisfy(conversation -> assertThat(conversation.userId()).isEqualTo("other"));
    }

    @Test
    void metadataWhitelistAndIdempotencyConflictAreEnforced() {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();
        ConversationService service = fixture.newService();
        String conversationId = service.createConversation("owner").conversationId();

        service.appendMessages(conversationId, "owner", List.of(MessageAppendCommand.text(
                "turn-1", "request-1", 0, ConversationRole.USER, "original")));

        assertThatThrownBy(() -> service.appendMessages(conversationId, "owner", List.of(
                MessageAppendCommand.text(
                        "turn-2", "request-1", 0, ConversationRole.USER, "different"))))
                .isInstanceOf(ConversationMemoryException.class)
                .satisfies(error -> assertThat(((ConversationMemoryException) error).getCode())
                        .isEqualTo(ConversationErrorCode.CONVERSATION_CONFLICT));
        assertThatThrownBy(() -> service.appendMessages(conversationId, "owner", List.of(
                new MessageAppendCommand(
                        null, "turn-3", "request-3", 0, ConversationRole.USER,
                        com.javaee.aiservice.conversation.model.ConversationMessageType.TEXT,
                        "content", null, null, java.util.Map.of("authorizationHeader", "secret")))))
                .isInstanceOf(ConversationMemoryException.class)
                .satisfies(error -> assertThat(((ConversationMemoryException) error).getCode())
                        .isEqualTo(ConversationErrorCode.INVALID_CONVERSATION_REQUEST));
    }
}
