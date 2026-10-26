package com.javaee.aiservice.review.m0.privacy;

import com.javaee.aiservice.review.m0.checklist.M0IdGenerator;
import com.javaee.aiservice.review.m0.model.RemoteConsent;
import com.javaee.aiservice.review.m0.model.RemotePreview;
import com.javaee.aiservice.review.m0.model.RemotePreviewStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class JdbcRemotePrivacyStore {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final M0IdGenerator ids;

    public JdbcRemotePrivacyStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            M0IdGenerator ids
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public RemotePreviewView createOwned(
            String ownerId,
            String caseId,
            ReviewJobType operation,
            long basisRevision,
            List<RemotePreviewSegment> segments,
            Instant now
    ) {
        requireOwnedCaseRevision(ownerId, caseId, basisRevision);
        String previewId = ids.next("preview_");
        List<RemotePreviewSegment> stable = List.copyOf(segments);
        String segmentsJson = RemotePayloads.segmentsJson(stable);
        String digest = RemotePayloads.payloadDigest(stable);
        jdbcTemplate.update("""
                        INSERT INTO review_m0_remote_preview(
                            preview_id, case_id, operation, status, basis_revision, segments_json,
                            payload_digest, version, created_by, created_at, updated_at
                        ) VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, 0, ?, ?, ?)
                        """,
                previewId, caseId, operation.name(), basisRevision, segmentsJson, digest,
                ownerId, timestamp(now), timestamp(now));
        return requirePreviewOwned(ownerId, caseId, previewId);
    }

    public Optional<RemotePreviewView> findPreviewOwned(String ownerId, String caseId, String previewId) {
        return jdbcTemplate.query("""
                        SELECT preview.preview_id, preview.case_id, preview.operation, preview.status,
                               preview.basis_revision, preview.segments_json, preview.payload_digest,
                               preview.version, preview.created_by, preview.created_at, preview.updated_at
                        FROM review_m0_remote_preview preview
                        JOIN review_m0_case review_case ON review_case.case_id = preview.case_id
                        WHERE preview.preview_id = ? AND preview.case_id = ? AND review_case.user_id = ?
                        """, this::mapPreview, previewId, caseId, ownerId).stream().findFirst();
    }

    public RemotePreviewView updateOwned(
            String ownerId,
            String caseId,
            String previewId,
            long expectedVersion,
            List<RemotePreviewSegment> segments,
            Instant now
    ) {
        RemotePreviewView current = requirePreviewOwned(ownerId, caseId, previewId);
        if (current.preview().version() != expectedVersion) {
            throw conflict("Remote preview changed concurrently");
        }
        if (current.preview().status() == RemotePreviewStatus.CONSUMED
                || current.preview().status() == RemotePreviewStatus.INVALIDATED) {
            throw conflict("Remote preview can no longer be edited");
        }
        requireImmutableSegmentIdentity(current.segments(), segments);
        String segmentsJson = RemotePayloads.segmentsJson(segments);
        String digest = RemotePayloads.payloadDigest(segments);
        int updated = jdbcTemplate.update("""
                        UPDATE review_m0_remote_preview preview
                        SET segments_json = ?, payload_digest = ?, status = 'READY_FOR_CONFIRMATION',
                            version = version + 1, updated_at = ?
                        WHERE preview_id = ? AND case_id = ? AND version = ?
                          AND status IN ('DRAFT', 'READY_FOR_CONFIRMATION', 'CONFIRMED')
                          AND EXISTS (SELECT 1 FROM review_m0_case review_case
                                      WHERE review_case.case_id = preview.case_id AND review_case.user_id = ?)
                        """,
                segmentsJson, digest, timestamp(now), previewId, caseId, expectedVersion, ownerId);
        if (updated != 1) {
            throw conflict("Remote preview changed concurrently");
        }
        return requirePreviewOwned(ownerId, caseId, previewId);
    }

    public RemoteConsent confirmOwned(
            String ownerId,
            String caseId,
            String previewId,
            long expectedVersion,
            String acknowledgedPayloadDigest,
            Instant now
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            RemotePreviewView current = lockPreviewOwned(ownerId, caseId, previewId);
            RemotePreview preview = current.preview();
            requireOwnedCaseRevision(ownerId, caseId, preview.basisRevision());
            if (preview.version() != expectedVersion) {
                throw conflict("Remote preview changed concurrently");
            }
            if (preview.status() != RemotePreviewStatus.READY_FOR_CONFIRMATION
                    || !preview.payloadDigest().equals(acknowledgedPayloadDigest)) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE,
                        "Remote preview digest or state is stale");
            }
            Instant confirmationTime = nextConfirmationTime(previewId, now);
            int updated = jdbcTemplate.update("""
                            UPDATE review_m0_remote_preview
                            SET status = 'CONFIRMED', version = version + 1, updated_at = ?
                            WHERE preview_id = ? AND case_id = ? AND version = ?
                              AND status = 'READY_FOR_CONFIRMATION' AND payload_digest = ?
                            """,
                    timestamp(confirmationTime), previewId, caseId, expectedVersion, acknowledgedPayloadDigest);
            if (updated != 1) {
                throw conflict("Remote preview changed concurrently");
            }
            String consentId = ids.next("consent_");
            jdbcTemplate.update("""
                            INSERT INTO review_m0_remote_consent(
                                consent_id, preview_id, case_id, operation, basis_revision, payload_digest,
                                confirmed_by, confirmed_at, consumed_by_job_id, consumed_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                            """,
                    consentId, previewId, caseId, preview.operation().name(), preview.basisRevision(),
                    preview.payloadDigest(), ownerId, timestamp(confirmationTime));
            return requireConsentOwned(ownerId, caseId, consentId).consent();
        }));
    }

    public Optional<ConsentPreview> findConsentOwned(String ownerId, String caseId, String consentId) {
        List<RemoteConsent> consents = jdbcTemplate.query("""
                        SELECT consent.consent_id, consent.preview_id, consent.case_id, consent.operation,
                               consent.basis_revision, consent.payload_digest, consent.confirmed_by,
                               consent.confirmed_at, consent.consumed_by_job_id, consent.consumed_at
                        FROM review_m0_remote_consent consent
                        JOIN review_m0_case review_case ON review_case.case_id = consent.case_id
                        WHERE consent.consent_id = ? AND consent.case_id = ? AND review_case.user_id = ?
                          AND consent.confirmed_by = ?
                        """, this::mapConsent, consentId, caseId, ownerId, ownerId);
        if (consents.isEmpty()) {
            return Optional.empty();
        }
        RemoteConsent consent = consents.get(0);
        Integer newer = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM review_m0_remote_consent
                        WHERE preview_id = ? AND confirmed_at > ?
                        """, Integer.class, consent.previewId(), timestamp(consent.confirmedAt()));
        boolean currentConfirmation = consent.consumedByJobId() != null || newer == null || newer == 0;
        return findPreviewOwned(ownerId, caseId, consent.previewId())
                .map(preview -> new ConsentPreview(consent, preview, currentConfirmation));
    }

    private Instant nextConfirmationTime(String previewId, Instant requested) {
        Timestamp latest = jdbcTemplate.queryForObject("""
                        SELECT MAX(confirmed_at) FROM review_m0_remote_consent WHERE preview_id = ?
                        """, Timestamp.class, previewId);
        if (latest == null || requested.isAfter(latest.toInstant())) {
            return requested;
        }
        return latest.toInstant().plusMillis(1);
    }

    private RemotePreviewView lockPreviewOwned(String ownerId, String caseId, String previewId) {
        List<RemotePreviewView> previews = jdbcTemplate.query("""
                        SELECT preview.preview_id, preview.case_id, preview.operation, preview.status,
                               preview.basis_revision, preview.segments_json, preview.payload_digest,
                               preview.version, preview.created_by, preview.created_at, preview.updated_at
                        FROM review_m0_remote_preview preview
                        JOIN review_m0_case review_case ON review_case.case_id = preview.case_id
                        WHERE preview.preview_id = ? AND preview.case_id = ? AND review_case.user_id = ?
                        FOR UPDATE
                        """, this::mapPreview, previewId, caseId, ownerId);
        if (previews.isEmpty()) {
            throw resourceNotFound();
        }
        return previews.get(0);
    }

    private RemotePreviewView requirePreviewOwned(String ownerId, String caseId, String previewId) {
        return findPreviewOwned(ownerId, caseId, previewId).orElseThrow(JdbcRemotePrivacyStore::resourceNotFound);
    }

    private ConsentPreview requireConsentOwned(String ownerId, String caseId, String consentId) {
        return findConsentOwned(ownerId, caseId, consentId).orElseThrow(JdbcRemotePrivacyStore::resourceNotFound);
    }

    private void requireOwnedCaseRevision(String ownerId, String caseId, long basisRevision) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM review_m0_case
                        WHERE case_id = ? AND user_id = ? AND basis_revision = ?
                        """, Integer.class, caseId, ownerId, basisRevision);
        if (count == null || count != 1) {
            Integer owned = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_m0_case WHERE case_id = ? AND user_id = ?",
                    Integer.class, caseId, ownerId);
            if (owned == null || owned != 1) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND, "Review case was not found");
            }
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE,
                    "Remote preview does not match the current basis revision");
        }
    }

    private static void requireImmutableSegmentIdentity(
            List<RemotePreviewSegment> existing, List<RemotePreviewSegment> edited
    ) {
        List<RemotePreviewSegment> stableEdited = edited.stream()
                .sorted(java.util.Comparator.comparing(RemotePreviewSegment::segmentId)).toList();
        if (existing.size() != stableEdited.size()) {
            throw invalid("Remote preview edit must preserve the segment set");
        }
        for (int index = 0; index < existing.size(); index++) {
            RemotePreviewSegment left = existing.get(index);
            RemotePreviewSegment right = stableEdited.get(index);
            if (!left.segmentId().equals(right.segmentId())
                    || !left.basisId().equals(right.basisId())
                    || !left.sourceLocator().equals(right.sourceLocator())) {
                throw invalid("Remote preview edit must preserve segment source identity");
            }
        }
    }

    private RemotePreviewView mapPreview(ResultSet resultSet, int rowNumber) throws SQLException {
        String segmentsJson = resultSet.getString("segments_json");
        List<RemotePreviewSegment> segments = RemotePayloads.readSegments(segmentsJson);
        String digest = resultSet.getString("payload_digest");
        if (!RemotePayloads.payloadDigest(segments).equals(digest)) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                    "Stored remote preview integrity check failed");
        }
        RemotePreview preview = new RemotePreview(
                resultSet.getString("preview_id"), resultSet.getString("case_id"),
                ReviewJobType.valueOf(resultSet.getString("operation")),
                RemotePreviewStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("basis_revision"), segmentsJson, digest,
                resultSet.getLong("version"), resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
        return new RemotePreviewView(preview, segments);
    }

    private RemoteConsent mapConsent(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp consumedAt = resultSet.getTimestamp("consumed_at");
        return new RemoteConsent(
                resultSet.getString("consent_id"), resultSet.getString("preview_id"),
                resultSet.getString("case_id"), ReviewJobType.valueOf(resultSet.getString("operation")),
                resultSet.getLong("basis_revision"), resultSet.getString("payload_digest"),
                resultSet.getString("confirmed_by"), resultSet.getTimestamp("confirmed_at").toInstant(),
                resultSet.getString("consumed_by_job_id"), consumedAt == null ? null : consumedAt.toInstant());
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }

    private static ReviewM0Exception conflict(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT, message);
    }

    private static ReviewM0Exception resourceNotFound() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND, "Remote preview or consent was not found");
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
