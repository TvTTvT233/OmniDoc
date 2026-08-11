package com.javaee.aiservice.conversation.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.conversation.model.Conversation;
import com.javaee.aiservice.conversation.model.ConversationCursor;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessagePage;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.model.ConversationPage;
import com.javaee.aiservice.conversation.model.ConversationRole;
import com.javaee.aiservice.conversation.model.ConversationStatus;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.conversation.service.ConversationErrorCode;
import com.javaee.aiservice.conversation.service.ConversationMemoryException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Repository
public class JdbcConversationStore implements ConversationStore {

    private static final Set<String> ALLOWED_METADATA_KEYS = Set.of(
            "legacyImported", "legacyUnparsed", "displayable", "source", "errorCode",
            "eventType", "label", "status", "toolName");
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<Conversation> conversationMapper = this::mapConversation;
    private final RowMapper<ConversationMessage> messageMapper = this::mapMessage;

    public JdbcConversationStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public Conversation create(Conversation conversation) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO ai_conversation(
                                conversation_id, user_id, title, status, next_sequence,
                                created_at, updated_at, archived_at, version
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    conversation.conversationId(),
                    conversation.userId(),
                    conversation.title(),
                    conversation.status().name(),
                    conversation.nextSequence(),
                    timestamp(conversation.createdAt()),
                    timestamp(conversation.updatedAt()),
                    timestamp(conversation.archivedAt()),
                    conversation.version());
            return conversation;
        } catch (DuplicateKeyException error) {
            throw conflict("Conversation already exists", error);
        }
    }

    @Override
    public Optional<Conversation> findOwned(String conversationId, String userId) {
        List<Conversation> rows = jdbcTemplate.query("""
                        SELECT conversation_id, user_id, title, status, next_sequence,
                               created_at, updated_at, archived_at, version
                        FROM ai_conversation
                        WHERE conversation_id = ? AND user_id = ?
                        """,
                conversationMapper,
                conversationId,
                userId);
        return rows.stream().findFirst();
    }

    @Override
    public ConversationPage listOwned(
            String userId,
            ConversationStatus status,
            ConversationCursor cursor,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT conversation_id, user_id, title, status, next_sequence,
                       created_at, updated_at, archived_at, version
                FROM ai_conversation
                WHERE user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (cursor != null) {
            sql.append(" AND (updated_at < ? OR (updated_at = ? AND conversation_id < ?))");
            Timestamp updatedAt = timestamp(cursor.updatedAt());
            args.add(updatedAt);
            args.add(updatedAt);
            args.add(cursor.conversationId());
        }
        sql.append(" ORDER BY updated_at DESC, conversation_id DESC LIMIT ?");
        args.add(limit + 1);

        List<Conversation> rows = jdbcTemplate.query(sql.toString(), conversationMapper, args.toArray());
        ConversationCursor nextCursor = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            Conversation last = rows.get(rows.size() - 1);
            nextCursor = new ConversationCursor(last.updatedAt(), last.conversationId());
        }
        return new ConversationPage(rows, nextCursor);
    }

    @Override
    public ConversationMessagePage listMessagesOwned(
            String conversationId,
            String userId,
            Long beforeSequence,
            int limit
    ) {
        requireOwned(conversationId, userId, false);
        String sql = """
                SELECT message_id, conversation_id, `sequence`, turn_id, client_request_id,
                       turn_ordinal, role, message_type, content, trace_id, tool_call_id,
                       metadata_json, content_hash, created_at
                FROM ai_conversation_message
                WHERE conversation_id = ?
                """ + (beforeSequence == null ? "" : " AND `sequence` < ?")
                + " ORDER BY `sequence` DESC LIMIT ?";

        List<ConversationMessage> rows = beforeSequence == null
                ? jdbcTemplate.query(sql, messageMapper, conversationId, limit + 1)
                : jdbcTemplate.query(sql, messageMapper, conversationId, beforeSequence, limit + 1);
        Long nextBefore = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            nextBefore = rows.get(rows.size() - 1).sequence();
        }
        Collections.reverse(rows);
        return new ConversationMessagePage(rows, nextBefore);
    }

    @Override
    public List<ConversationMessage> appendMessages(
            String conversationId,
            String userId,
            List<MessageAppendCommand> commands
    ) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        return Objects.requireNonNull(transactionTemplate.execute(status ->
                appendMessagesLocked(conversationId, userId, commands)));
    }

    @Override
    public Conversation importLegacyConversation(
            Conversation conversation,
            List<MessageAppendCommand> commands
    ) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Optional<Conversation> existing = findOwned(conversation.conversationId(), conversation.userId());
            if (existing.isPresent()) {
                return existing.get();
            }
            create(conversation);
            appendMessagesLocked(conversation.conversationId(), conversation.userId(), commands);
            return requireOwned(conversation.conversationId(), conversation.userId(), false);
        }));
    }

    @Override
    public Conversation changeStatus(
            String conversationId,
            String userId,
            ConversationStatus expectedStatus,
            ConversationStatus targetStatus
    ) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Conversation current = requireOwned(conversationId, userId, true);
            if (current.status() == targetStatus) {
                return current;
            }
            if (current.status() != expectedStatus) {
                throw conflict("Conversation status changed concurrently", null);
            }
            Instant now = Instant.now();
            Instant archivedAt = targetStatus == ConversationStatus.ARCHIVED ? now : null;
            int updated = jdbcTemplate.update("""
                            UPDATE ai_conversation
                            SET status = ?, updated_at = ?, archived_at = ?, version = version + 1
                            WHERE conversation_id = ? AND user_id = ? AND status = ? AND version = ?
                            """,
                    targetStatus.name(),
                    timestamp(now),
                    timestamp(archivedAt),
                    conversationId,
                    userId,
                    expectedStatus.name(),
                    current.version());
            if (updated != 1) {
                throw conflict("Conversation status changed concurrently", null);
            }
            return requireOwned(conversationId, userId, false);
        }));
    }

    @Override
    public void deleteOwned(String conversationId, String userId) {
        transactionTemplate.executeWithoutResult(status -> {
            requireOwned(conversationId, userId, true);
            int deleted = jdbcTemplate.update(
                    "DELETE FROM ai_conversation WHERE conversation_id = ? AND user_id = ?",
                    conversationId,
                    userId);
            if (deleted != 1) {
                throw notFound();
            }
        });
    }

    private List<ConversationMessage> appendMessagesLocked(
            String conversationId,
            String userId,
            List<MessageAppendCommand> commands
    ) {
        Conversation conversation = requireOwned(conversationId, userId, true);
        if (conversation.status() == ConversationStatus.ARCHIVED) {
            throw new ConversationMemoryException(
                    ConversationErrorCode.CONVERSATION_ARCHIVED,
                    "Archived conversations cannot accept new messages");
        }

        long nextSequence = conversation.nextSequence();
        int inserted = 0;
        List<ConversationMessage> results = new ArrayList<>(commands.size());
        for (MessageAppendCommand command : commands) {
            validate(command);
            String contentHash = sha256(command.content());
            Optional<ConversationMessage> existing = findIdempotent(conversationId, command);
            if (existing.isPresent()) {
                ConversationMessage stored = existing.get();
                if (!stored.contentHash().equals(contentHash)
                        || !Objects.equals(stored.toolCallId(), command.toolCallId())
                        || !stored.metadata().equals(command.metadata())) {
                    throw conflict("Idempotency key was reused with different content", null);
                }
                results.add(stored);
                continue;
            }

            Instant createdAt = Instant.now();
            String metadataJson = writeMetadata(command.metadata());
            jdbcTemplate.update("""
                            INSERT INTO ai_conversation_message(
                                message_id, conversation_id, `sequence`, turn_id, client_request_id,
                                turn_ordinal, role, message_type, content, trace_id, tool_call_id,
                                metadata_json, content_hash, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    command.messageId(),
                    conversationId,
                    nextSequence,
                    command.turnId(),
                    command.clientRequestId(),
                    command.turnOrdinal(),
                    command.role().name(),
                    command.messageType().name(),
                    command.content(),
                    command.traceId(),
                    command.toolCallId(),
                    metadataJson,
                    contentHash,
                    timestamp(createdAt));
            results.add(new ConversationMessage(
                    command.messageId(), conversationId, nextSequence, command.turnId(),
                    command.clientRequestId(), command.turnOrdinal(), command.role(),
                    command.messageType(), command.content(), command.traceId(), command.toolCallId(),
                    command.metadata(), contentHash, createdAt));
            nextSequence++;
            inserted++;
        }

        if (inserted > 0) {
            jdbcTemplate.update("""
                            UPDATE ai_conversation
                            SET next_sequence = ?, updated_at = ?, version = version + 1
                            WHERE conversation_id = ? AND user_id = ?
                            """,
                    nextSequence,
                    timestamp(Instant.now()),
                    conversationId,
                    userId);
        }
        return List.copyOf(results);
    }

    private Optional<ConversationMessage> findIdempotent(
            String conversationId,
            MessageAppendCommand command
    ) {
        List<ConversationMessage> rows = jdbcTemplate.query("""
                        SELECT message_id, conversation_id, `sequence`, turn_id, client_request_id,
                               turn_ordinal, role, message_type, content, trace_id, tool_call_id,
                               metadata_json, content_hash, created_at
                        FROM ai_conversation_message
                        WHERE conversation_id = ? AND client_request_id = ?
                          AND role = ? AND message_type = ? AND turn_ordinal = ?
                        """,
                messageMapper,
                conversationId,
                command.clientRequestId(),
                command.role().name(),
                command.messageType().name(),
                command.turnOrdinal());
        return rows.stream().findFirst();
    }

    private Conversation requireOwned(String conversationId, String userId, boolean forUpdate) {
        String sql = """
                SELECT conversation_id, user_id, title, status, next_sequence,
                       created_at, updated_at, archived_at, version
                FROM ai_conversation
                WHERE conversation_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        List<Conversation> rows = jdbcTemplate.query(sql, conversationMapper, conversationId);
        if (rows.isEmpty() || !rows.get(0).userId().equals(userId)) {
            throw notFound();
        }
        return rows.get(0);
    }

    private void validate(MessageAppendCommand command) {
        requireText(command.turnId(), "turnId");
        requireText(command.clientRequestId(), "clientRequestId");
        if (command.messageId().length() > 64
                || command.turnId().length() > 64
                || command.clientRequestId().length() > 128
                || (command.traceId() != null && command.traceId().length() > 64)
                || (command.toolCallId() != null && command.toolCallId().length() > 128)) {
            throw invalid("Message identifiers exceed the storage contract");
        }
        if ((command.messageType() == ConversationMessageType.TOOL_CALL
                || command.messageType() == ConversationMessageType.TOOL_RESULT)
                && (command.toolCallId() == null || command.toolCallId().isBlank())) {
            throw invalid("Tool messages require toolCallId");
        }
        if (!ALLOWED_METADATA_KEYS.containsAll(command.metadata().keySet())) {
            throw invalid("Message metadata contains non-whitelisted fields");
        }
        command.metadata().forEach((key, value) -> {
            if ((key.equals("legacyImported") || key.equals("legacyUnparsed") || key.equals("displayable"))
                    && !(value instanceof Boolean)) {
                throw invalid("Boolean message metadata has an invalid value");
            }
            if ((key.equals("source") || key.equals("errorCode") || key.equals("eventType")
                    || key.equals("label") || key.equals("status") || key.equals("toolName"))
                    && (!(value instanceof String text) || text.length() > 256)) {
                throw invalid("Text message metadata has an invalid value");
            }
        });
    }

    private Conversation mapConversation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Conversation(
                resultSet.getString("conversation_id"),
                resultSet.getString("user_id"),
                resultSet.getString("title"),
                ConversationStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("next_sequence"),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at")),
                instant(resultSet.getTimestamp("archived_at")),
                resultSet.getLong("version"));
    }

    private ConversationMessage mapMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ConversationMessage(
                resultSet.getString("message_id"),
                resultSet.getString("conversation_id"),
                resultSet.getLong("sequence"),
                resultSet.getString("turn_id"),
                resultSet.getString("client_request_id"),
                resultSet.getInt("turn_ordinal"),
                ConversationRole.valueOf(resultSet.getString("role")),
                ConversationMessageType.valueOf(resultSet.getString("message_type")),
                resultSet.getString("content"),
                resultSet.getString("trace_id"),
                resultSet.getString("tool_call_id"),
                readMetadata(resultSet.getString("metadata_json")),
                resultSet.getString("content_hash"),
                instant(resultSet.getTimestamp("created_at")));
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception error) {
            throw invalid("Message metadata is not JSON serializable", error);
        }
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, METADATA_TYPE);
        } catch (Exception error) {
            throw new ConversationMemoryException(
                    ConversationErrorCode.CONVERSATION_CONFLICT,
                    "Stored message metadata is invalid",
                    error);
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
    }

    private static ConversationMemoryException notFound() {
        return new ConversationMemoryException(
                ConversationErrorCode.CONVERSATION_NOT_FOUND,
                "Conversation was not found");
    }

    private static ConversationMemoryException conflict(String message, Throwable cause) {
        return new ConversationMemoryException(ConversationErrorCode.CONVERSATION_CONFLICT, message, cause);
    }

    private static ConversationMemoryException invalid(String message) {
        return invalid(message, null);
    }

    private static ConversationMemoryException invalid(String message, Throwable cause) {
        return new ConversationMemoryException(ConversationErrorCode.INVALID_CONVERSATION_REQUEST, message, cause);
    }
}
