package com.javaee.aiservice.review.m0.privacy;

import com.javaee.aiservice.review.m0.checklist.M003TestFixture;
import com.javaee.aiservice.review.m0.extraction.ChecklistExtractionRequest;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.RemoteCallState;
import com.javaee.aiservice.review.m0.model.RemoteConsent;
import com.javaee.aiservice.review.m0.model.RemotePreviewStatus;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemotePrivacyAuthorizationTest {
    @Test
    void previewHasOperationRevisionDigestAndEditingInvalidatesOldConfirmation() {
        Setup setup = setup("case-preview");
        RemotePreviewView draft = setup.previewService().createOwned(
                "owner", "case-preview", ReviewJobType.CHECKLIST_EXTRACTION, 1,
                List.of(segment("Sensitive synthetic preview")));
        assertThat(draft.preview().status()).isEqualTo(RemotePreviewStatus.DRAFT);
        assertThat(draft.preview().operation()).isEqualTo(ReviewJobType.CHECKLIST_EXTRACTION);
        assertThat(draft.preview().basisRevision()).isEqualTo(1);
        assertThat(draft.preview().payloadDigest()).hasSize(64);

        RemotePreviewView ready = setup.previewService().editOwned(
                "owner", "case-preview", draft.preview().previewId(), 0,
                List.of(segment("First redacted version")));
        RemoteConsent oldConsent = setup.previewService().confirmOwned(
                "owner", "case-preview", ready.preview().previewId(), 1,
                ready.preview().payloadDigest());
        RemotePreviewView editedAgain = setup.previewService().editOwned(
                "owner", "case-preview", ready.preview().previewId(), 2,
                List.of(segment("Second redacted version")));
        RemoteConsent newConsent = setup.previewService().confirmOwned(
                "owner", "case-preview", editedAgain.preview().previewId(), 3,
                editedAgain.preview().payloadDigest());
        RemotePreviewView editedBack = setup.previewService().editOwned(
                "owner", "case-preview", editedAgain.preview().previewId(), 4,
                List.of(segment("First redacted version")));
        RemoteConsent latestConsent = setup.previewService().confirmOwned(
                "owner", "case-preview", editedBack.preview().previewId(), 5,
                editedBack.preview().payloadDigest());
        ReviewJob oldJob = leasedJob(setup.fixture(), "case-preview", "job-old", oldConsent.consentId(), "worker-old");

        assertThat(oldConsent.payloadDigest()).isNotEqualTo(newConsent.payloadDigest());
        assertThat(oldConsent.payloadDigest()).isEqualTo(latestConsent.payloadDigest());
        assertThatThrownBy(() -> setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-preview", oldJob.jobId(), "worker-old", oldConsent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(2)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE);
        assertNoRemoteSideEffects(setup.fixture(), oldConsent.consentId(), oldJob.jobId());
    }

    @Test
    void revisionOperationCrossOwnerAndCrossCaseMismatchesHaveNoSideEffects() {
        Setup setup = setup("case-mismatch");
        RemoteConsent consent = confirmedConsent(setup, "case-mismatch", "Redacted payload");
        ReviewJob job = leasedJob(
                setup.fixture(), "case-mismatch", "job-mismatch", consent.consentId(), "worker");

        assertThatThrownBy(() -> setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-mismatch", job.jobId(), "worker", consent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 2, M003TestFixture.NOW.plusSeconds(2)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE);
        assertThatThrownBy(() -> setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-mismatch", job.jobId(), "worker", consent.consentId(),
                ReviewJobType.FORMAL_REVIEW, 1, M003TestFixture.NOW.plusSeconds(2)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_REQUIRED);
        assertThatThrownBy(() -> setup.fixture().consentVerifier().authorizeForLogicalJob(
                "other", "case-mismatch", job.jobId(), "worker", consent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(2)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
        assertThatThrownBy(() -> setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "different-case", job.jobId(), "worker", consent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(2)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
        assertNoRemoteSideEffects(setup.fixture(), consent.consentId(), job.jobId());

        ReviewJob differentlyBound = leasedJob(
                setup.fixture(), "case-mismatch", "job-different-consent", "consent-not-authorized", "worker-2");
        assertThatThrownBy(() -> setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-mismatch", differentlyBound.jobId(), "worker-2", consent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(2)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_REQUIRED);
        assertNoRemoteSideEffects(setup.fixture(), consent.consentId(), differentlyBound.jobId());
    }

    @Test
    void authorizationPersistsSendIntentAndActualPayloadDigestMatchesConfirmation() {
        Setup setup = setup("case-digest");
        RemoteConsent consent = confirmedConsent(setup, "case-digest", "Only redacted bytes leave");
        ReviewJob job = leasedJob(setup.fixture(), "case-digest", "job-digest", consent.consentId(), "worker");

        RemoteSendAuthorization authorization = setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-digest", job.jobId(), "worker", consent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(2));

        assertThat(authorization.disposition())
                .isEqualTo(RemoteSendAuthorization.Disposition.SEND_AUTHORIZED);
        assertThat(authorization.remoteCall().state()).isEqualTo(RemoteCallState.SEND_INTENT);
        assertThat(authorization.payloadDigest()).isEqualTo(consent.payloadDigest());
        assertThat(RemotePayloads.sha256(authorization.payloadBytes())).isEqualTo(consent.payloadDigest());
        assertThat(new String(authorization.payloadBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("sourceLocator", "basis-remote");
    }

    @Test
    void concurrentLogicalJobsConsumeOneConsentExactlyOnce() throws Exception {
        Setup setup = setup("case-race");
        RemoteConsent consent = confirmedConsent(setup, "case-race", "Redacted race payload");
        ReviewJob first = leasedJob(setup.fixture(), "case-race", "job-race-a", consent.consentId(), "worker-a");
        ReviewJob second = leasedJob(setup.fixture(), "case-race", "job-race-b", consent.consentId(), "worker-b");
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> attempts = List.of(
                    authorizationAttempt(setup, first, consent, "worker-a"),
                    authorizationAttempt(setup, second, consent, "worker-b"));
            List<String> outcomes = executor.invokeAll(attempts).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }).toList();

            assertThat(outcomes).filteredOn(value -> value.startsWith("SUCCESS:")).hasSize(1);
            assertThat(outcomes).contains(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_CONSUMED.name());
            assertThat(setup.fixture().database().jdbc().queryForObject(
                    "SELECT COUNT(*) FROM review_m0_remote_call", Integer.class)).isEqualTo(1);
            String winner = setup.fixture().database().jdbc().queryForObject(
                    "SELECT consumed_by_job_id FROM review_m0_remote_consent WHERE consent_id = ?",
                    String.class, consent.consentId());
            assertThat(winner).isIn(first.jobId(), second.jobId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sendIntentCrashBecomesOutcomeUnknownAndNewAuthorizationIsRequired() {
        Setup setup = setup("case-crash");
        RemoteConsent firstConsent = confirmedConsent(setup, "case-crash", "First authorized payload");
        ReviewJob firstJob = leasedJob(
                setup.fixture(), "case-crash", "job-crash", firstConsent.consentId(), "worker");
        setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-crash", firstJob.jobId(), "worker", firstConsent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(2));

        assertThatThrownBy(() -> setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-crash", firstJob.jobId(), "worker", firstConsent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(3)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_OUTCOME_UNKNOWN);
        assertThat(setup.fixture().remoteCallStore().findOwned(
                "owner", "case-crash", firstJob.jobId()).orElseThrow().state())
                .isEqualTo(RemoteCallState.OUTCOME_UNKNOWN);

        RemotePreviewView oldPreview = setup.fixture().privacyStore().findPreviewOwned(
                "owner", "case-crash", firstConsent.previewId()).orElseThrow();
        RemotePreviewView edited = setup.previewService().editOwned(
                "owner", "case-crash", firstConsent.previewId(), oldPreview.preview().version(),
                List.of(segment("New authorization payload")));
        RemoteConsent secondConsent = setup.previewService().confirmOwned(
                "owner", "case-crash", edited.preview().previewId(), edited.preview().version(),
                edited.preview().payloadDigest());
        ReviewJob secondJob = leasedJob(
                setup.fixture(), "case-crash", "job-retry", secondConsent.consentId(), "worker-new");

        assertThat(setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-crash", secondJob.jobId(), "worker-new", secondConsent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(4)).disposition())
                .isEqualTo(RemoteSendAuthorization.Disposition.SEND_AUTHORIZED);
    }

    @Test
    void persistedSuccessfulResponseIsRecoveredWithoutAnotherSendAuthorization() {
        Setup setup = setup("case-response");
        RemoteConsent consent = confirmedConsent(setup, "case-response", "Redacted success payload");
        ReviewJob job = leasedJob(
                setup.fixture(), "case-response", "job-response", consent.consentId(), "worker");
        setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-response", job.jobId(), "worker", consent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(2));
        setup.fixture().remoteCallStore().recordResponse(
                job.jobId(), "worker", "provider-1", M003TestFixture.DIGEST_B,
                M003TestFixture.NOW.plusSeconds(3));

        RemoteSendAuthorization recovered = setup.fixture().consentVerifier().authorizeForLogicalJob(
                "owner", "case-response", job.jobId(), "worker", consent.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(4));

        assertThat(recovered.disposition())
                .isEqualTo(RemoteSendAuthorization.Disposition.RESPONSE_ALREADY_RECORDED);
        assertThat(recovered.payloadBytes()).isNull();
        assertThat(setup.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call WHERE job_id = ?",
                Integer.class, job.jobId())).isEqualTo(1);
    }

    @Test
    void localOnlyCaseCannotCreateRemotePreviewAndCrossOwnerReadsLookMissing() {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase("case-local", "owner", ProcessingMode.LOCAL_ONLY);
        RemotePreviewService service = new RemotePreviewService(
                fixture.caseStore(), fixture.checklistStore(), fixture.privacyStore(), M003TestFixture.CLOCK);

        assertThatThrownBy(() -> service.createOwned(
                "owner", "case-local", ReviewJobType.CHECKLIST_EXTRACTION, 0,
                List.of(segment("No remote default"))))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_REQUIRED);

        Setup remote = setup("case-hidden");
        RemotePreviewView preview = remote.previewService().createOwned(
                "owner", "case-hidden", ReviewJobType.CHECKLIST_EXTRACTION, 1,
                List.of(segment("Hidden preview")));
        assertThat(remote.fixture().privacyStore().findPreviewOwned(
                "other", "case-hidden", preview.preview().previewId())).isEmpty();
        assertThatThrownBy(() -> remote.previewService().editOwned(
                "other", "case-hidden", preview.preview().previewId(), 0,
                List.of(segment("Probe"))))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
    }

    private static Setup setup(String caseId) {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase(caseId, "owner", ProcessingMode.REMOTE_MODEL);
        fixture.attachReadyBasis("owner", caseId, 0, "basis-remote");
        return new Setup(fixture, new RemotePreviewService(
                fixture.caseStore(), fixture.checklistStore(), fixture.privacyStore(), M003TestFixture.CLOCK));
    }

    private static RemoteConsent confirmedConsent(Setup setup, String caseId, String text) {
        RemotePreviewView draft = setup.previewService().createOwned(
                "owner", caseId, ReviewJobType.CHECKLIST_EXTRACTION, 1, List.of(segment(text)));
        RemotePreviewView ready = setup.previewService().editOwned(
                "owner", caseId, draft.preview().previewId(), draft.preview().version(),
                List.of(segment(text)));
        return setup.previewService().confirmOwned(
                "owner", caseId, ready.preview().previewId(), ready.preview().version(),
                ready.preview().payloadDigest());
    }

    private static RemotePreviewSegment segment(String text) {
        return new RemotePreviewSegment(
                "seg-1", "basis-remote",
                new OfficialBasisSourceLocator(null, 1, null, null, "block-1", null),
                true, text);
    }

    private static ReviewJob leasedJob(
            M003TestFixture fixture,
            String caseId,
            String jobId,
            String consentId,
            String workerId
    ) {
        ChecklistExtractionRequest request = new ChecklistExtractionRequest(
                1, ProcessingMode.REMOTE_MODEL, consentId);
        String json = request.canonicalJson();
        ReviewJob queued = new ReviewJob(
                jobId, caseId, "owner", ReviewJobType.CHECKLIST_EXTRACTION, ReviewJobStatus.QUEUED,
                json, canonicalDigest(json), null, "idem-" + jobId,
                0, 1, 0, null, null, null, null, null, 0,
                M003TestFixture.NOW, null, null, null);
        fixture.jobStore().create(queued);
        return fixture.jobStore().acquireNext(
                workerId, M003TestFixture.NOW.plusSeconds(1), Duration.ofMinutes(5)).orElseThrow();
    }

    private static String canonicalDigest(String json) {
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
            return CanonicalJson.sha256(parsed);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static Callable<String> authorizationAttempt(
            Setup setup, ReviewJob job, RemoteConsent consent, String worker
    ) {
        return () -> {
            try {
                setup.fixture().consentVerifier().authorizeForLogicalJob(
                        "owner", job.caseId(), job.jobId(), worker, consent.consentId(),
                        ReviewJobType.CHECKLIST_EXTRACTION, 1, M003TestFixture.NOW.plusSeconds(2));
                return "SUCCESS:" + job.jobId();
            } catch (ReviewM0Exception error) {
                return error.errorCode().name();
            }
        };
    }

    private static void assertNoRemoteSideEffects(
            M003TestFixture fixture, String consentId, String jobId
    ) {
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT consumed_by_job_id FROM review_m0_remote_consent WHERE consent_id = ?",
                String.class, consentId)).isNull();
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call WHERE job_id = ?",
                Integer.class, jobId)).isZero();
    }

    private record Setup(M003TestFixture fixture, RemotePreviewService previewService) {
    }
}
