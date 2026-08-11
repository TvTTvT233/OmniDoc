package com.javaee.aiservice.memory.summary;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public final class JdbcConversationSummaryStore implements ConversationSummaryStore {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<StoredConversationSummary> rowMapper = this::map;

    public JdbcConversationSummaryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<StoredConversationSummary> findOwned(String conversationId, String userId) {
        List<StoredConversationSummary> values = jdbcTemplate.query("""
                        SELECT s.conversation_id, s.schema_version, s.summary_version,
                               s.from_sequence, s.to_sequence, s.source_digest, s.summary_json,
                               s.generated_at, s.updated_at
                        FROM ai_conversation_summary s
                        JOIN ai_conversation c ON c.conversation_id = s.conversation_id
                        WHERE s.conversation_id = ? AND c.user_id = ?
                        """,
                rowMapper,
                conversationId,
                userId);
        return values.stream().findFirst();
    }

    @Override
    public SummaryWriteResult saveCas(StoredConversationSummary summary, long expectedPreviousVersion) {
        if (summary.summaryVersion() != expectedPreviousVersion + 1) {
            throw new IllegalArgumentException("Summary version must increment exactly once");
        }
        Optional<StoredConversationSummary> existing = find(summary.conversationId());
        if (existing.isPresent() && existing.get().summaryVersion() == summary.summaryVersion()) {
            return equivalent(existing.get(), summary) ? SummaryWriteResult.IDEMPOTENT : SummaryWriteResult.CONFLICT;
        }
        if (expectedPreviousVersion == 0) {
            try {
                jdbcTemplate.update("""
                                INSERT INTO ai_conversation_summary(
                                    conversation_id, schema_version, summary_version, from_sequence,
                                    to_sequence, source_digest, summary_json, generated_at, updated_at
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        summary.conversationId(), summary.schemaVersion(), summary.summaryVersion(),
                        summary.fromSequence(), summary.toSequence(), summary.sourceDigest(),
                        summary.summaryJson(), Timestamp.from(summary.generatedAt()), Timestamp.from(summary.updatedAt()));
                return SummaryWriteResult.STORED;
            } catch (DuplicateKeyException conflict) {
                return find(summary.conversationId()).filter(current -> equivalent(current, summary))
                        .map(ignored -> SummaryWriteResult.IDEMPOTENT)
                        .orElse(SummaryWriteResult.CONFLICT);
            }
        }

        int updated = jdbcTemplate.update("""
                        UPDATE ai_conversation_summary
                        SET schema_version = ?, summary_version = ?, from_sequence = ?, to_sequence = ?,
                            source_digest = ?, summary_json = ?, generated_at = ?, updated_at = ?
                        WHERE conversation_id = ? AND summary_version = ?
                        """,
                summary.schemaVersion(), summary.summaryVersion(), summary.fromSequence(), summary.toSequence(),
                summary.sourceDigest(), summary.summaryJson(), Timestamp.from(summary.generatedAt()),
                Timestamp.from(summary.updatedAt()), summary.conversationId(), expectedPreviousVersion);
        if (updated == 1) {
            return SummaryWriteResult.STORED;
        }
        return find(summary.conversationId()).filter(current -> equivalent(current, summary))
                .map(ignored -> SummaryWriteResult.IDEMPOTENT)
                .orElse(SummaryWriteResult.CONFLICT);
    }

    private Optional<StoredConversationSummary> find(String conversationId) {
        return jdbcTemplate.query("""
                        SELECT conversation_id, schema_version, summary_version, from_sequence,
                               to_sequence, source_digest, summary_json, generated_at, updated_at
                        FROM ai_conversation_summary
                        WHERE conversation_id = ?
                        """,
                rowMapper,
                conversationId).stream().findFirst();
    }

    private static boolean equivalent(StoredConversationSummary left, StoredConversationSummary right) {
        return left.conversationId().equals(right.conversationId())
                && left.schemaVersion().equals(right.schemaVersion())
                && left.summaryVersion() == right.summaryVersion()
                && left.fromSequence() == right.fromSequence()
                && left.toSequence() == right.toSequence()
                && left.sourceDigest().equals(right.sourceDigest())
                && left.summaryJson().equals(right.summaryJson());
    }

    private StoredConversationSummary map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredConversationSummary(
                resultSet.getString("conversation_id"),
                resultSet.getString("schema_version"),
                resultSet.getLong("summary_version"),
                resultSet.getLong("from_sequence"),
                resultSet.getLong("to_sequence"),
                resultSet.getString("source_digest"),
                resultSet.getString("summary_json"),
                resultSet.getTimestamp("generated_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
