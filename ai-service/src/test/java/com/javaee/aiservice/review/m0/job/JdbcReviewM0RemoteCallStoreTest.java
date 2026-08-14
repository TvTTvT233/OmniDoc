package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.RemoteCall;
import com.javaee.aiservice.review.m0.model.RemoteCallState;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.RevisionKind;
import com.javaee.aiservice.review.m0.store.JdbcReviewCaseStore;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

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
        assertThat(store.findOwned("owner", "case-remote", "job-remote")).contains(first);
        assertThat(store.findOwned("other", "case-remote", "job-remote")).isEmpty();
        assertThat(store.findOwned("owner", "other-case", "job-remote")).isEmpty();
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
        assertThat(store.findOwned("owner", "case-digest", "job-digest")).isEmpty();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT consumed_by_job_id FROM review_m0_remote_consent WHERE consent_id = 'consent-digest'",
                String.class)).isNull();
    }

    @Test
    void crossOwnerAndCaseConsentIsHiddenAndLeavesConsentAndCallStateUntouched() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-job", "owner-job");
        ReviewM0StoreTestData.createCase(fixture, "case-consent", "owner-consent");
        insertConsent(fixture, "case-consent", "owner-consent", "consent-cross", 0, "a".repeat(64), "CONFIRMED");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-cross", "case-job", "owner-job",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-cross", "worker");
        JdbcReviewM0RemoteCallStore store = new JdbcReviewM0RemoteCallStore(
                fixture.jdbc(), fixture.transactions());

        assertThatThrownBy(() -> store.beginSendIntent(intent(
                "call-cross", "job-cross", "consent-cross", "a".repeat(64)),
                "worker", NOW.plusSeconds(1)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND);
        assertUnconsumedWithoutCall(fixture, "consent-cross", "job-cross");
        assertThat(store.findOwned("owner-consent", "case-consent", "job-cross")).isEmpty();
        assertThat(store.findOwned("owner-job", "case-job", "job-cross")).isEmpty();
    }

    @Test
    void mismatchedConsentOwnerOnTheSameCaseCannotAuthorizeSendIntent() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-owner", "owner");
        insertConsent(fixture, "case-owner", "different-owner", "consent-owner", 0, "a".repeat(64), "CONFIRMED");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-owner", "case-owner", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-owner", "worker");
        JdbcReviewM0RemoteCallStore store = new JdbcReviewM0RemoteCallStore(
                fixture.jdbc(), fixture.transactions());

        assertThatThrownBy(() -> store.beginSendIntent(intent(
                "call-owner", "job-owner", "consent-owner", "a".repeat(64)),
                "worker", NOW.plusSeconds(1)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND);
        assertUnconsumedWithoutCall(fixture, "consent-owner", "job-owner");
    }

    @Test
    void oldBasisRevisionAndInvalidatedPreviewCannotAuthorizeSendIntent() {
        ReviewM0JdbcTestFixture staleFixture = fixtureWithConsent("case-stale", "consent-stale");
        new JdbcReviewCaseStore(staleFixture.jdbc(), staleFixture.transactions())
                .advanceRevisionOwned("owner", "case-stale", 0, RevisionKind.BASIS, "BASIS_CHANGED");
        ReviewM0StoreTestData.createAndLeaseJob(
                staleFixture, "job-stale", "case-stale", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-stale", "worker");
        JdbcReviewM0RemoteCallStore staleStore = new JdbcReviewM0RemoteCallStore(
                staleFixture.jdbc(), staleFixture.transactions());

        assertThatThrownBy(() -> staleStore.beginSendIntent(intent(
                "call-stale", "job-stale", "consent-stale", "a".repeat(64)),
                "worker", NOW.plusSeconds(1)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE);
        assertUnconsumedWithoutCall(staleFixture, "consent-stale", "job-stale");

        ReviewM0JdbcTestFixture invalidatedFixture = fixtureWithConsent(
                "case-invalidated", "consent-invalidated");
        invalidatedFixture.jdbc().update(
                "UPDATE review_m0_remote_preview SET status = 'INVALIDATED' WHERE preview_id = 'preview-consent-invalidated'");
        ReviewM0StoreTestData.createAndLeaseJob(
                invalidatedFixture, "job-invalidated", "case-invalidated", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-invalidated", "worker");
        JdbcReviewM0RemoteCallStore invalidatedStore = new JdbcReviewM0RemoteCallStore(
                invalidatedFixture.jdbc(), invalidatedFixture.transactions());

        assertThatThrownBy(() -> invalidatedStore.beginSendIntent(intent(
                "call-invalidated", "job-invalidated", "consent-invalidated", "a".repeat(64)),
                "worker", NOW.plusSeconds(1)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE);
        assertUnconsumedWithoutCall(invalidatedFixture, "consent-invalidated", "job-invalidated");
    }

    @Test
    void operationMismatchCannotAuthorizeSendIntentEvenWithTheSameDigest() {
        ReviewM0JdbcTestFixture fixture = fixtureWithConsent("case-operation", "consent-operation");
        fixture.jdbc().execute("ALTER TABLE review_m0_remote_consent "
                + "DROP CONSTRAINT ck_review_m0_remote_consent_operation");
        fixture.jdbc().update("UPDATE review_m0_remote_consent SET operation = 'FORMAL_REVIEW' "
                + "WHERE consent_id = 'consent-operation'");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-operation", "case-operation", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-operation", "worker");
        JdbcReviewM0RemoteCallStore store = new JdbcReviewM0RemoteCallStore(
                fixture.jdbc(), fixture.transactions());

        assertThatThrownBy(() -> store.beginSendIntent(intent(
                "call-operation", "job-operation", "consent-operation", "a".repeat(64)),
                "worker", NOW.plusSeconds(1)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_REQUIRED);
        assertUnconsumedWithoutCall(fixture, "consent-operation", "job-operation");
    }

    @Test
    void consumedConsentWithoutItsMatchingRemoteCallCannotBeConsumedAgain() {
        ReviewM0JdbcTestFixture fixture = fixtureWithConsent("case-repeat", "consent-repeat");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-repeat", "case-repeat", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-repeat", "worker");
        fixture.jdbc().update("""
                        UPDATE review_m0_remote_consent
                        SET consumed_by_job_id = 'job-repeat', consumed_at = ?
                        WHERE consent_id = 'consent-repeat'
                        """, Timestamp.from(NOW));
        JdbcReviewM0RemoteCallStore store = new JdbcReviewM0RemoteCallStore(
                fixture.jdbc(), fixture.transactions());

        assertThatThrownBy(() -> store.beginSendIntent(intent(
                "call-repeat", "job-repeat", "consent-repeat", "a".repeat(64)),
                "worker", NOW.plusSeconds(1)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_CONSUMED);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT consumed_by_job_id FROM review_m0_remote_consent WHERE consent_id = 'consent-repeat'",
                String.class)).isEqualTo("job-repeat");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call WHERE job_id = 'job-repeat'", Integer.class)).isZero();
    }

    @Test
    void concurrentConsentConsumptionAllowsExactlyOneLogicalJob() throws Exception {
        ReviewM0JdbcTestFixture fixture = fixtureWithConsent("case-race", "consent-race");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-race-a", "case-race", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-race-a", "worker-a");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-race-b", "case-race", "owner",
                ReviewJobType.CHECKLIST_EXTRACTION, "idem-race-b", "worker-b");
        JdbcReviewM0RemoteCallStore store = new JdbcReviewM0RemoteCallStore(
                fixture.jdbc(), fixture.transactions());
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> attempts = List.of(
                    sendAttempt(store, "call-race-a", "job-race-a", "worker-a"),
                    sendAttempt(store, "call-race-b", "job-race-b", "worker-b"));
            List<String> outcomes = executor.invokeAll(attempts).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }).toList();

            assertThat(outcomes.stream().filter(value -> value.startsWith("SUCCESS:"))).hasSize(1);
            assertThat(outcomes).contains(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_CONSUMED.name());
            String winner = fixture.jdbc().queryForObject(
                    "SELECT consumed_by_job_id FROM review_m0_remote_consent WHERE consent_id = 'consent-race'",
                    String.class);
            assertThat(winner).isIn("job-race-a", "job-race-b");
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM review_m0_remote_call", Integer.class)).isEqualTo(1);
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT job_id FROM review_m0_remote_call", String.class)).isEqualTo(winner);
        } finally {
            executor.shutdownNow();
        }
    }

    private static ReviewM0JdbcTestFixture fixtureWithConsent(String caseId, String consentId) {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, caseId, "owner");
        insertConsent(fixture, caseId, "owner", consentId, 0, "a".repeat(64), "CONFIRMED");
        return fixture;
    }

    private static void insertConsent(
            ReviewM0JdbcTestFixture fixture,
            String caseId,
            String ownerId,
            String consentId,
            long basisRevision,
            String payloadDigest,
            String previewStatus
    ) {
        fixture.jdbc().update("""
                        INSERT INTO review_m0_remote_preview(
                            preview_id, case_id, operation, status, basis_revision, segments_json,
                            payload_digest, version, created_by, created_at, updated_at
                        ) VALUES (?, ?, 'CHECKLIST_EXTRACTION', ?, ?, '[]', ?, 0, ?, ?, ?)
                        """,
                "preview-" + consentId, caseId, previewStatus, basisRevision, payloadDigest, ownerId,
                Timestamp.from(NOW), Timestamp.from(NOW));
        fixture.jdbc().update("""
                        INSERT INTO review_m0_remote_consent(
                            consent_id, preview_id, case_id, operation, basis_revision, payload_digest,
                            confirmed_by, confirmed_at, consumed_by_job_id, consumed_at
                        ) VALUES (?, ?, ?, 'CHECKLIST_EXTRACTION', ?, ?, ?, ?, NULL, NULL)
                        """,
                consentId, "preview-" + consentId, caseId, basisRevision, payloadDigest, ownerId,
                Timestamp.from(NOW));
    }

    private static RemoteCall intent(String callId, String jobId, String consentId, String digest) {
        return new RemoteCall(
                callId, jobId, consentId, digest, RemoteCallState.SEND_INTENT,
                null, null, NOW, NOW);
    }

    private static void assertUnconsumedWithoutCall(
            ReviewM0JdbcTestFixture fixture,
            String consentId,
            String jobId
    ) {
        assertThat(fixture.jdbc().queryForObject(
                "SELECT consumed_by_job_id FROM review_m0_remote_consent WHERE consent_id = ?",
                String.class, consentId)).isNull();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call WHERE job_id = ?",
                Integer.class, jobId)).isZero();
    }

    private static Callable<String> sendAttempt(
            JdbcReviewM0RemoteCallStore store,
            String callId,
            String jobId,
            String workerId
    ) {
        return () -> {
            try {
                RemoteCall call = store.beginSendIntent(
                        intent(callId, jobId, "consent-race", "a".repeat(64)),
                        workerId, NOW.plusSeconds(1));
                return "SUCCESS:" + call.jobId();
            } catch (ReviewM0Exception error) {
                return error.errorCode().name();
            }
        };
    }
}
