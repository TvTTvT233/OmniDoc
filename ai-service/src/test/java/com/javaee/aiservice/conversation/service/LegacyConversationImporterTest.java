package com.javaee.aiservice.conversation.service;

import com.javaee.aiservice.conversation.ConversationPersistenceTestFixture;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.ConversationRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyConversationImporterTest {

    @Test
    void ownerCheckedImportPreservesOrderAndMarksUnparsedEntriesWithoutGuessing() {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();
        fixture.properties().getLegacy().getImport().setEnabled(true);
        AtomicInteger reads = new AtomicInteger();
        LegacyConversationSource source = (conversationId, userId) -> {
            reads.incrementAndGet();
            if (!conversationId.equals("legacy-1") || !userId.equals("owner")) {
                return Optional.empty();
            }
            return Optional.of(new LegacyConversationSnapshot(
                    conversationId,
                    userId,
                    List.of("User: exact question", "Assistant: exact answer", "user: ambiguous")));
        };
        ConversationService service = fixture.newService(source);

        service.requireOwned("legacy-1", "owner");
        List<ConversationMessage> messages = service.listMessages("legacy-1", "owner", null, 10).items();

        assertThat(messages).extracting(ConversationMessage::sequence).containsExactly(1L, 2L, 3L);
        assertThat(messages).extracting(ConversationMessage::role)
                .containsExactly(ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.SYSTEM);
        assertThat(messages).extracting(ConversationMessage::messageType)
                .containsExactly(ConversationMessageType.TEXT, ConversationMessageType.TEXT,
                        ConversationMessageType.EVENT);
        assertThat(messages.get(2).content()).isEqualTo("user: ambiguous");
        assertThat(messages.get(2).metadata())
                .containsEntry("legacyImported", true)
                .containsEntry("legacyUnparsed", true);
        assertThat(service.legacyHistory("legacy-1", "owner", 10))
                .containsExactly("User: exact question", "Assistant: exact answer", "Event: user: ambiguous");

        service.requireOwned("legacy-1", "owner");
        assertThat(reads).hasValue(1);
        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation_message WHERE conversation_id = 'legacy-1'",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void foreignOwnerCannotImportOrLearnWhetherLegacyConversationExists() {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();
        fixture.properties().getLegacy().getImport().setEnabled(true);
        ConversationService service = fixture.newService((conversationId, userId) -> {
            if (userId.equals("owner")) {
                return Optional.of(new LegacyConversationSnapshot(conversationId, userId, List.of()));
            }
            return Optional.empty();
        });

        assertThatThrownBy(() -> service.requireOwned("legacy-secret", "other"))
                .isInstanceOf(ConversationMemoryException.class)
                .satisfies(error -> assertThat(((ConversationMemoryException) error).getCode())
                        .isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE conversation_id = 'legacy-secret'",
                Integer.class)).isZero();
    }

    @Test
    void permanentDeleteClearsOwnedLegacySourceSoConversationCannotBeReimported() {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();
        fixture.properties().getLegacy().getImport().setEnabled(true);
        AtomicBoolean present = new AtomicBoolean(true);
        LegacyConversationSource source = new LegacyConversationSource() {
            @Override
            public Optional<LegacyConversationSnapshot> findOwned(String conversationId, String userId) {
                if (!present.get() || !conversationId.equals("legacy-delete") || !userId.equals("owner")) {
                    return Optional.empty();
                }
                return Optional.of(new LegacyConversationSnapshot(
                        conversationId, userId, List.of("User: remove me")));
            }

            @Override
            public void deleteOwned(String conversationId, String userId) {
                if (conversationId.equals("legacy-delete") && userId.equals("owner")) {
                    present.set(false);
                }
            }
        };
        ConversationService service = fixture.newService(source);

        service.requireOwned("legacy-delete", "owner");
        service.delete("legacy-delete", "owner");

        assertThat(present).isFalse();
        assertThatThrownBy(() -> service.requireOwned("legacy-delete", "owner"))
                .isInstanceOf(ConversationMemoryException.class)
                .satisfies(error -> assertThat(((ConversationMemoryException) error).getCode())
                        .isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE conversation_id = 'legacy-delete'",
                Integer.class)).isZero();
    }

    @Test
    void legacyCleanupFailureDoesNotCommitAReimportableDatabaseDelete() {
        ConversationPersistenceTestFixture fixture = new ConversationPersistenceTestFixture();
        fixture.properties().getLegacy().getImport().setEnabled(true);
        LegacyConversationSource source = new LegacyConversationSource() {
            @Override
            public Optional<LegacyConversationSnapshot> findOwned(String conversationId, String userId) {
                return Optional.of(new LegacyConversationSnapshot(
                        conversationId, userId, List.of("User: keep until cleanup succeeds")));
            }

            @Override
            public void deleteOwned(String conversationId, String userId) {
                throw new ConversationMemoryException(
                        ConversationErrorCode.CONVERSATION_CONFLICT,
                        "legacy cleanup unavailable");
            }
        };
        ConversationService service = fixture.newService(source);
        service.requireOwned("legacy-failure", "owner");

        assertThatThrownBy(() -> service.delete("legacy-failure", "owner"))
                .isInstanceOf(ConversationMemoryException.class)
                .satisfies(error -> assertThat(((ConversationMemoryException) error).getCode())
                        .isEqualTo(ConversationErrorCode.CONVERSATION_CONFLICT));
        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE conversation_id = 'legacy-failure'",
                Integer.class)).isOne();
    }
}
