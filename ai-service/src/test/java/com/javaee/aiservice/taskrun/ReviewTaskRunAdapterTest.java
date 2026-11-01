package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import com.javaee.aiservice.review.m0.report.*;
import com.javaee.aiservice.review.m0.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewTaskRunAdapterTest {
    private final ReviewCaseStore cases = mock(ReviewCaseStore.class);
    private final ReviewM0JobStore jobs = mock(ReviewM0JobStore.class);
    private final ReviewReportReader reports = mock(ReviewReportReader.class);
    private final ReviewTaskRunAdapter adapter = new ReviewTaskRunAdapter(provider(cases), provider(jobs), provider(reports));
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(T object) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(object);
        return provider;
    }
    private ReviewJob ownedJob(ReviewJobStatus status) {
        return ownedJob(status, status == ReviewJobStatus.SUCCEEDED ? new ReviewJobResultRef("CHECKLIST_DRAFT", "draft") : null);
    }
    private ReviewJob ownedJob(ReviewJobStatus status, ReviewJobResultRef result) {
        ReviewCase reviewCase = new ReviewCase("case", "owner", "PRIVATE_TITLE", ProcessingMode.LOCAL_ONLY,
                0, 0, 0, 0, 0, 0, null, "newest-report", Instant.EPOCH, Instant.EPOCH);
        when(cases.findOwned("owner", "case")).thenReturn(Optional.of(reviewCase));
        ReviewJob job = new ReviewJob("job", "case", "owner", ReviewJobType.FORMAL_REVIEW, status,
                "PRIVATE_REQUEST", "a".repeat(64), null, "idempotency", 0, 0, 1, null, null,
                status == ReviewJobStatus.CANCEL_REQUESTED ? Instant.EPOCH : null, result,
                status == ReviewJobStatus.FAILED ? new ReviewJobError(ReviewM0ErrorCode.values()[0], false, "PRIVATE_ERROR") : null,
                1, Instant.EPOCH, Instant.EPOCH, null, status.terminal() ? Instant.EPOCH : null);
        when(jobs.findOwned("owner", "case", "job")).thenReturn(Optional.of(job));
        return job;
    }
    @Test void ownerCheckedBeforeUnscopedCheckpointsOrReports() {
        assertThat(adapter.findOwned("other", "case", "job")).isEmpty();
        verifyNoInteractions(jobs, reports);
        ownedJob(ReviewJobStatus.QUEUED);
        when(jobs.findOwned("owner", "case", "wrong")).thenReturn(Optional.empty());
        assertThat(adapter.findOwned("owner", "case", "wrong")).isEmpty();
        verify(jobs, never()).listCheckpoints(anyString());
    }
    @ParameterizedTest @EnumSource(ReviewJobStatus.class)
    void executionStatesDoNotInventToolCallsOrChronologicalHistory(ReviewJobStatus state) {
        ownedJob(state);
        when(jobs.listCheckpoints("job")).thenReturn(List.of(new ReviewJobCheckpoint("job", "INPUT_VERIFIED",
                "PRIVATE_CHECKPOINT_BODY", "a".repeat(64), 1, Instant.EPOCH)));
        var view = adapter.findOwned("owner", "case", "job").orElseThrow();
        assertThat(view.status()).isEqualTo(state == ReviewJobStatus.CANCEL_REQUESTED ? "RUNNING" : state.name());
        assertThat(view.cancellationRequested()).isEqualTo(state == ReviewJobStatus.CANCEL_REQUESTED);
        assertThat(view.steps().get(0).startedAt()).isNull();
        assertThat(view.steps().get(0).kind()).isEqualTo("CHECKPOINT_SUMMARY");
        assertThat(view.toolCalls()).isEmpty(); assertThat(view.events()).isEmpty();
    }
    @Test void failedAssessmentAndOutdatedHistoricalReportRemainSuccessfulExecution() {
        ownedJob(ReviewJobStatus.SUCCEEDED, new ReviewJobResultRef("REPORT", "historical"));
        ReviewReport report = new ReviewReport("historical", "case", "job", "v1", "m0",
                ReviewReportFreshness.OUTDATED, ReviewAssessment.M0_NOT_READY, "a".repeat(64),
                "snapshot", "upload", 1, 1, 1, 1, null, 1, 0, 0, Instant.EPOCH, Instant.EPOCH, "changed");
        when(reports.findOwned("owner", "case", "historical")).thenReturn(Optional.of(new PublishedReview(report, List.of(), null)));
        var view = adapter.findOwned("owner", "case", "job").orElseThrow();
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.businessStatus()).isEqualTo("M0_NOT_READY");
        assertThat(view.result().resourceId()).isEqualTo("historical");
        assertThat(view.result().freshness()).isEqualTo("OUTDATED");
        assertThat(view.waitingReason()).isNull();
    }
    @Test void checklistResultIsNotMisrepresentedAsReport() {
        ownedJob(ReviewJobStatus.SUCCEEDED);
        var view = adapter.findOwned("owner", "case", "job").orElseThrow();
        assertThat(view.result().kind()).isEqualTo("CHECKLIST_DRAFT");
        assertThat(view.businessStatus()).isNull();
        verifyNoInteractions(reports);
    }
}
