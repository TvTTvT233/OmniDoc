package com.javaee.aiservice.review.m0.extraction;

import com.javaee.aiservice.review.m0.checklist.ChecklistCandidate;
import com.javaee.aiservice.review.m0.checklist.ChecklistDraftView;
import com.javaee.aiservice.review.m0.checklist.M003TestFixture;
import com.javaee.aiservice.review.m0.job.ReviewM0JobDispatcher;
import com.javaee.aiservice.review.m0.job.ReviewM0JobHandlerRegistry;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.RemoteCallState;
import com.javaee.aiservice.review.m0.model.RemoteConsent;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewSegment;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewService;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewView;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChecklistExtractionJobHandlerTest {
    @Test
    void localOnlyExtractionNeverConstructsOrCallsRemoteClientAndPublishesCandidateOnly() {
        M003TestFixture fixture = localSetup("case-local-job");
        AtomicInteger remoteConstructions = new AtomicInteger();
        AtomicInteger remoteCalls = new AtomicInteger();
        ChecklistExtractionJobHandler handler = handler(
                fixture,
                segments -> List.of(fixture.officialCandidate("basis-job", "Local candidate")),
                () -> {
                    remoteConstructions.incrementAndGet();
                    return new DeterministicRemoteCandidateClient(
                            remoteCalls, bytes -> new RemoteExtractionResult("never", bytes, List.of()));
                });
        queueJob(fixture, "case-local-job", "job-local", ProcessingMode.LOCAL_ONLY, null);

        ReviewJob result = dispatch(fixture, handler, "worker-local");

        assertThat(result.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(result.result().resultType()).isEqualTo(ChecklistExtractionJobHandler.RESULT_TYPE);
        assertThat(remoteConstructions).hasValue(0);
        assertThat(remoteCalls).hasValue(0);
        ChecklistDraftView draft = fixture.checklistService().requireLatestDraftOwned("owner", "case-local-job");
        assertThat(draft.lifecycle().name()).isEqualTo("CANDIDATE_DRAFT");
        assertThat(draft.extractionJobId()).isEqualTo("job-local");
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_checklist_snapshot", Integer.class)).isZero();
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call", Integer.class)).isZero();
    }

    @Test
    void remoteSuccessSendsExactlyConfirmedBytesAndResponseCreatesCandidateOnly() {
        RemoteSetup setup = remoteSetup("case-remote-success", "Sanitized preview body");
        AtomicInteger constructions = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<byte[]> sent = new AtomicReference<>();
        ChecklistExtractionJobHandler handler = handler(
                setup.fixture(), segments -> List.of(), () -> {
                    constructions.incrementAndGet();
                    return new DeterministicRemoteCandidateClient(calls, bytes -> {
                        sent.set(bytes.clone());
                        return new RemoteExtractionResult(
                                "provider-request-1", "synthetic response".getBytes(StandardCharsets.UTF_8),
                                List.of(setup.fixture().officialCandidate("basis-job", "Remote candidate")));
                    });
                });
        queueJob(
                setup.fixture(), "case-remote-success", "job-remote-success",
                ProcessingMode.REMOTE_MODEL, setup.consent().consentId());

        ReviewJob result = dispatch(setup.fixture(), handler, "worker-remote");

        assertThat(result.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(constructions).hasValue(1);
        assertThat(calls).hasValue(1);
        assertThat(sha256(sent.get())).isEqualTo(setup.consent().payloadDigest());
        assertThat(setup.fixture().remoteCallStore().findOwned(
                "owner", "case-remote-success", "job-remote-success").orElseThrow().state())
                .isEqualTo(RemoteCallState.RESPONSE_RECORDED);
        assertThat(setup.fixture().checklistService().requireLatestDraftOwned(
                "owner", "case-remote-success").items())
                .extracting(item -> item.displayName()).containsExactly("Remote candidate");
        assertNoFormalOutputs(setup.fixture());
    }

    @Test
    void remoteKnownFailureFailsJobWithoutSnapshotFindingOrCandidate() {
        RemoteSetup setup = remoteSetup("case-remote-fail", "Failure-safe preview body");
        AtomicInteger calls = new AtomicInteger();
        ChecklistExtractionJobHandler handler = new ChecklistExtractionJobHandler(
                setup.fixture().caseStore(), setup.fixture().checklistService(),
                (owner, caseId, revision) -> List.of(), new DeterministicLocalCandidateExtractor(segments -> List.of()),
                setup.fixture().consentVerifier(), setup.fixture().remoteCallStore(),
                () -> payload -> {
                    calls.incrementAndGet();
                    throw new RemoteCandidateClientException(false, "Safe remote failure", null);
                }, M003TestFixture.CLOCK);
        queueJob(
                setup.fixture(), "case-remote-fail", "job-remote-fail",
                ProcessingMode.REMOTE_MODEL, setup.consent().consentId());

        ReviewJob result = dispatch(setup.fixture(), handler, "worker-fail");

        assertThat(result.status()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(result.error().errorCode()).isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_MODEL_FAILED);
        assertThat(calls).hasValue(1);
        assertThat(setup.fixture().checklistStore().findLatestDraftOwned(
                "owner", "case-remote-fail")).isEmpty();
        assertThat(setup.fixture().remoteCallStore().findOwned(
                "owner", "case-remote-fail", "job-remote-fail").orElseThrow().state())
                .isEqualTo(RemoteCallState.OUTCOME_UNKNOWN);
        assertNoFormalOutputs(setup.fixture());
    }

    @Test
    void remoteUnknownOutcomeFailsClosedAndCannotAutomaticallyResend() {
        RemoteSetup setup = remoteSetup("case-remote-unknown", "Unknown-safe preview body");
        AtomicInteger constructions = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        ChecklistExtractionJobHandler handler = new ChecklistExtractionJobHandler(
                setup.fixture().caseStore(), setup.fixture().checklistService(),
                (owner, caseId, revision) -> List.of(), new DeterministicLocalCandidateExtractor(segments -> List.of()),
                setup.fixture().consentVerifier(), setup.fixture().remoteCallStore(),
                () -> {
                    constructions.incrementAndGet();
                    return payload -> {
                        calls.incrementAndGet();
                        throw new RemoteCandidateClientException(true, "Unknown remote outcome", null);
                    };
                }, M003TestFixture.CLOCK);
        queueJob(
                setup.fixture(), "case-remote-unknown", "job-remote-unknown",
                ProcessingMode.REMOTE_MODEL, setup.consent().consentId());

        ReviewJob result = dispatch(setup.fixture(), handler, "worker-unknown");

        assertThat(result.status()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(result.error().errorCode()).isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_OUTCOME_UNKNOWN);
        assertThat(constructions).hasValue(1);
        assertThat(calls).hasValue(1);
        assertThat(setup.fixture().remoteCallStore().findOwned(
                "owner", "case-remote-unknown", "job-remote-unknown").orElseThrow().state())
                .isEqualTo(RemoteCallState.OUTCOME_UNKNOWN);
        assertNoFormalOutputs(setup.fixture());
    }

    @Test
    void unlocatedRemoteTextIsRejectedAndRecordedResponseIsNeverPublishedAsOfficialRule() {
        RemoteSetup setup = remoteSetup("case-remote-invalid", "Invalid-result-safe preview");
        AtomicInteger calls = new AtomicInteger();
        ChecklistCandidate unlocated = new ChecklistCandidate(
                "Unlocated remote text", true, RequirementApplicability.APPLIES, 1,
                new FileNameRule(FileNameRuleKind.NONE, null, null), List.of());
        ChecklistExtractionJobHandler handler = handler(
                setup.fixture(), segments -> List.of(),
                () -> new DeterministicRemoteCandidateClient(calls, bytes -> new RemoteExtractionResult(
                        "provider-invalid", "invalid response".getBytes(StandardCharsets.UTF_8),
                        List.of(unlocated))));
        queueJob(
                setup.fixture(), "case-remote-invalid", "job-remote-invalid",
                ProcessingMode.REMOTE_MODEL, setup.consent().consentId());

        ReviewJob result = dispatch(setup.fixture(), handler, "worker-invalid");

        assertThat(result.status()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(result.error().errorCode()).isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REMOTE_MODEL_FAILED);
        assertThat(calls).hasValue(1);
        assertThat(setup.fixture().checklistStore().findLatestDraftOwned(
                "owner", "case-remote-invalid")).isEmpty();
        assertThat(setup.fixture().remoteCallStore().findOwned(
                "owner", "case-remote-invalid", "job-remote-invalid").orElseThrow().state())
                .isEqualTo(RemoteCallState.RESPONSE_RECORDED);
        assertNoFormalOutputs(setup.fixture());
    }

    @Test
    void sensitiveBodiesNeverEnterJobErrorsCheckpointsRemoteAuditOrExceptionMessages() {
        String marker = "SYNTHETIC_PRIVATE_BODY_78421";
        RemoteSetup setup = remoteSetup("case-sensitive", marker);
        ChecklistExtractionJobHandler handler = new ChecklistExtractionJobHandler(
                setup.fixture().caseStore(), setup.fixture().checklistService(),
                (owner, caseId, revision) -> List.of(), new DeterministicLocalCandidateExtractor(segments -> List.of()),
                setup.fixture().consentVerifier(), setup.fixture().remoteCallStore(),
                () -> payload -> {
                    throw new RemoteCandidateClientException(true, "Remote request failed", null);
                }, M003TestFixture.CLOCK);
        queueJob(
                setup.fixture(), "case-sensitive", "job-sensitive",
                ProcessingMode.REMOTE_MODEL, setup.consent().consentId());

        ReviewJob failed = dispatch(setup.fixture(), handler, "worker-sensitive");

        assertThat(failed.error().message()).doesNotContain(marker);
        assertThat(setup.fixture().database().jdbc().queryForObject("""
                        SELECT COUNT(*) FROM review_m0_job
                        WHERE request_json LIKE ? OR error_message LIKE ?
                        """, Integer.class, "%" + marker + "%", "%" + marker + "%")).isZero();
        assertThat(setup.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_job_checkpoint WHERE checkpoint_json LIKE ?",
                Integer.class, "%" + marker + "%")).isZero();
        assertThat(setup.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call WHERE provider_request_id LIKE ?",
                Integer.class, "%" + marker + "%")).isZero();
        assertNoFormalOutputs(setup.fixture());
    }

    private static M003TestFixture localSetup(String caseId) {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase(caseId, "owner", ProcessingMode.LOCAL_ONLY);
        fixture.attachReadyBasis("owner", caseId, 0, "basis-job");
        return fixture;
    }

    private static RemoteSetup remoteSetup(String caseId, String previewText) {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase(caseId, "owner", ProcessingMode.REMOTE_MODEL);
        fixture.attachReadyBasis("owner", caseId, 0, "basis-job");
        RemotePreviewService service = new RemotePreviewService(
                fixture.caseStore(), fixture.checklistStore(), fixture.privacyStore(), M003TestFixture.CLOCK);
        RemotePreviewSegment segment = new RemotePreviewSegment(
                "segment-1", "basis-job",
                new OfficialBasisSourceLocator(null, 1, null, null, "block-1", null),
                true, previewText);
        RemotePreviewView draft = service.createOwned(
                "owner", caseId, ReviewJobType.CHECKLIST_EXTRACTION, 1, List.of(segment));
        RemotePreviewView ready = service.editOwned(
                "owner", caseId, draft.preview().previewId(), draft.preview().version(), List.of(segment));
        RemoteConsent consent = service.confirmOwned(
                "owner", caseId, ready.preview().previewId(), ready.preview().version(),
                ready.preview().payloadDigest());
        return new RemoteSetup(fixture, consent);
    }

    private static ChecklistExtractionJobHandler handler(
            M003TestFixture fixture,
            java.util.function.Function<List<OfficialBasisSegment>, List<ChecklistCandidate>> localBehavior,
            RemoteCandidateClientFactory remoteFactory
    ) {
        OfficialBasisContentProvider provider = (owner, caseId, revision) -> List.of(
                new OfficialBasisSegment(
                        "basis-job",
                        new OfficialBasisSourceLocator(null, 1, null, null, "block-1", null),
                        "Synthetic official basis body"));
        return new ChecklistExtractionJobHandler(
                fixture.caseStore(), fixture.checklistService(), provider,
                new DeterministicLocalCandidateExtractor(localBehavior), fixture.consentVerifier(),
                fixture.remoteCallStore(), remoteFactory, M003TestFixture.CLOCK);
    }

    private static ReviewJob queueJob(
            M003TestFixture fixture,
            String caseId,
            String jobId,
            ProcessingMode mode,
            String consentId
    ) {
        ChecklistExtractionRequest request = new ChecklistExtractionRequest(1, mode, consentId);
        String json = request.canonicalJson();
        ReviewJob job = new ReviewJob(
                jobId, caseId, "owner", ReviewJobType.CHECKLIST_EXTRACTION, ReviewJobStatus.QUEUED,
                json, digest(json), null, "idem-" + jobId,
                0, 1, 0, null, null, null, null, null, 0,
                M003TestFixture.NOW, null, null, null);
        return fixture.jobStore().create(job);
    }

    private static ReviewJob dispatch(
            M003TestFixture fixture, ChecklistExtractionJobHandler handler, String workerId
    ) {
        ReviewM0JobDispatcher dispatcher = new ReviewM0JobDispatcher(
                fixture.jobStore(), new ReviewM0JobHandlerRegistry(List.of(handler)));
        return dispatcher.runNext(
                workerId, M003TestFixture.NOW, Duration.ofMinutes(5), 1).orElseThrow();
    }

    private static void assertNoFormalOutputs(M003TestFixture fixture) {
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_checklist_snapshot", Integer.class)).isZero();
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isZero();
    }

    private static String digest(String json) {
        try {
            return CanonicalJson.sha256(
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private record RemoteSetup(M003TestFixture fixture, RemoteConsent consent) {
    }
}
