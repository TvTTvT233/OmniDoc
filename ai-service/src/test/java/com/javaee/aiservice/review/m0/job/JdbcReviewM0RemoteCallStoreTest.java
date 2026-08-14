package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.RemoteCall;
import com.javaee.aiservice.review.m0.model.RemoteCallState;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.store.JdbcReviewCaseStore;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReviewM0RemoteCallStoreTest {
    private static final Instant NOW = ReviewM0StoreTestData.T0.plusSeconds(10);

    @Test
    void consentAndSendIntentAreConsumedOncePerLogicalJob() {
        ReviewM0JdbcTestFixture fixture = fixtureWithConsent("case-remote", "consent");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-remote", "case-remote", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-remote", "worker");
        JdbcReviewM0RemoteCallStore store = new JdbcReviewM0RemoteCallStore(
                fixture.jdbc(), fixture.transactions());
        RemoteCall intent = new RemoteCall(
                "call", "job-remote", "consent", "a".repeat(64), RemoteCallState.SEND_INTENT,
                null, null, NOW, NOW);

        RemoteCall first = store.beginSendIntent(intent, "worker", NOW.plusSeconds(1));
        RemoteCall replay = store.beginSendIntent(intent, "worker", NOW.plusSeconds(2));

        assertThat(replay).isEqualTo(first);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT consumed_by_job_id FROM review_m0_remote_consent WHERE consent_id = 'consent'",
                String.class)).isEqualTo("job-remote");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call WHERE job_id = 'job-remote'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void responseRecordingIsIdempotentAndOutcomeUnknownForbidsAutomaticResend() {
        ReviewM0JdbcTestFixture fixture = fixtureWithConsent("case-outcome", "consent-outcome");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-outcome", "case-outcome", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-outcome", "worker");
        JdbcReviewM0RemoteCallStore store = new JdbcReviewM0RemoteCallStore(
                fixture.jdbc(), fixture.transactions());
        store.beginSendIntent(new RemoteCall(
                "call-outcome", "job-outcome", "consent-outcome", "a".repeat(64),
                RemoteCallState.SEND_INTENT, null, null, NOW, NOW), "worker", NOW.plusSeconds(1));

        RemoteCall unknown = store.markOutcomeUnknown("job-outcome", "worker", NOW.plusSeconds(2));
        assertThat(unknown.state()).isEqualTo(RemoteCallState.OUTCOME_UNKNOWN);
        assertThatThrownBy(() -> store.recordResponse(
                "job-outcome", "worker", "provider-id", "b".repeat(64), NOW.plusSeconds(3)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_OUTCOME_UNKNOWN);
        assertThat(store.beginSendIntent(new RemoteCall(
                "another-call", "job-outcome", "consent-outcome", "a".repeat(64),
                RemoteCallState.SEND_INTENT, null, null, NOW, NOW), "worker", NOW.plusSeconds(4)).state())
                .isEqualTo(RemoteCallState.OUTCOME_UNKNOWN);
    }

    @Test
    void confirmedPayloadDigestMustMatchActualSendBytes() {
        ReviewM0JdbcTestFixture fixture = fixtureWithConsent("case-digest", "consent-digest");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-digest", "case-digest", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-digest", "worker");
        JdbcReviewM0RemoteCallStore store = new JdbcReviewM0RemoteCallStore(
                fixture.jdbc(), fixture.transactions());

        assertThatThrownBy(() -> store.beginSendIntent(new RemoteCall(
                "call-digest", "job-digest", "consent-digest", "b".repeat(64),
                RemoteCallState.SEND_INTENT, null, null, NOW, NOW), "worker", NOW.plusSeconds(1)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE);
        assertThat(store.findByJob("job-digest")).isEmpty();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT consumed_by_job_id FROM review_m0_remote_consent WHERE consent_id = 'consent-digest'",
                String.class)).isNull();
    }

    private static ReviewM0JdbcTestFixture fixtureWithConsent(String caseId, String consentId) {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, caseId, "owner");
        fixture.jdbc().update("""
                        INSERT INTO review_m0_remote_preview(
                            preview_id, case_id, operation, status, basis_revision, segments_json,
                            payload_digest, version, created_by, created_at, updated_at
                        ) VALUES (?, ?, 'CHECKLIST_EXTRACTION', 'CONFIRMED', 0, '[]', ?, 0, 'owner', ?, ?)
                        """,
                "preview-" + consentId, caseId, "a".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW));
        fixture.jdbc().update("""
                        INSERT INTO review_m0_remote_consent(
                            consent_id, preview_id, case_id, operation, basis_revision, payload_digest,
                            confirmed_by, confirmed_at, consumed_by_job_id, consumed_at
                        ) VALUES (?, ?, ?, 'CHECKLIST_EXTRACTION', 0, ?, 'owner', ?, NULL, NULL)
                        """,
                consentId, "preview-" + consentId, caseId, "a".repeat(64), Timestamp.from(NOW));
        return fixture;
    }
}
