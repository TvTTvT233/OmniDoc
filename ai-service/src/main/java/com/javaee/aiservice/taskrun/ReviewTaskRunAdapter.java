package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import com.javaee.aiservice.review.m0.report.ReviewReportReader;
import com.javaee.aiservice.review.m0.model.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class ReviewTaskRunAdapter {
    private final ObjectProvider<ReviewCaseStore> cases;
    private final ObjectProvider<ReviewM0JobStore> jobs;
    private final ObjectProvider<ReviewReportReader> reports;
    public ReviewTaskRunAdapter(ObjectProvider<ReviewCaseStore> cases,
            ObjectProvider<ReviewM0JobStore> jobs, ObjectProvider<ReviewReportReader> reports) {
        this.cases = cases; this.jobs = jobs; this.reports = reports;
    }
    public Optional<TaskRunView> findOwned(String userId, String caseId, String jobId) {
        ReviewCaseStore caseStore = cases.getIfAvailable();
        ReviewM0JobStore store = jobs.getIfAvailable();
        if (caseStore == null || store == null) throw new org.springframework.dao.DataAccessResourceFailureException("Review unavailable");
        if (userId == null || caseStore.findOwned(userId, caseId).isEmpty()) return Optional.empty();
        Optional<ReviewJob> found = store.findOwned(userId, caseId, jobId);
        if (found.isEmpty()) return Optional.empty();
        ReviewJob job = found.get();
        String business = null;
        String freshness = null;
        TaskRunView.Result result = null;
        if (job.result() != null) {
            if ("REPORT".equals(job.result().resultType())) {
                ReviewReportReader reader = reports.getIfAvailable();
                if (reader == null) throw new org.springframework.dao.DataAccessResourceFailureException("Report unavailable");
                var published = reader.findOwned(userId, caseId, job.result().resultId());
                if (published.isEmpty()) return Optional.empty();
                var report = published.get().report();
                if (!jobId.equals(report.jobId())) return Optional.empty();
                business = report.assessment().name();
                freshness = report.freshness().name();
            }
            result = new TaskRunView.Result(job.result().resultType(), job.result().resultId(), business, freshness);
        }
        List<TaskRunView.Step> steps = store.listCheckpoints(jobId).stream()
                .filter(c -> Set.of("INPUT_VERIFIED", "REVIEW_COMPUTED").contains(c.checkpointKey()))
                .map(c -> new TaskRunView.Step(c.checkpointKey(), "CHECKPOINT_SUMMARY", "SUCCEEDED",
                        null, null, null, c.updatedAt())).toList();
        return Optional.of(new TaskRunView(1, "review:" + jobId, "REVIEW", jobId, job.jobType().name(),
                job.status() == ReviewJobStatus.CANCEL_REQUESTED ? "RUNNING" : job.status().name(),
                job.status().name(), business, null, job.cancelRequestedAt() != null,
                job.attempt(), job.version(), job.createdAt(), job.startedAt(), job.finishedAt(), Instant.now(),
                List.of(), steps, List.of(), result, List.of(),
                new TaskRunView.Capabilities("DATABASE", "SNAPSHOT_AND_CHECKPOINTS", true,
                        List.of("SOURCE_REVIEW_RECOVERY_ONLY", "NO_COMPLETE_EVENT_HISTORY", "KNOWN_CHECKPOINTS_ONLY"))));
    }
}
