package com.javaee.aiservice.review.m0.report;

import com.javaee.aiservice.review.m0.evidence.EvidenceDraft;
import com.javaee.aiservice.review.m0.model.EvidenceRef;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewFinding;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;
import com.javaee.aiservice.review.m0.review.FindingDraft;
import com.javaee.aiservice.review.m0.review.ReviewJobRequestEnvelope;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class ReviewReportAssembler {
    private final Clock clock;
    private final Function<String, String> ids;

    public ReviewReportAssembler(Clock clock) {
        this(clock, ReviewM0Ids::next);
    }

    public ReviewReportAssembler(Clock clock, Function<String, String> ids) {
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public ReportBundle assemble(
            ReviewJob job,
            ReviewJobRequestEnvelope request,
            List<FindingDraft> drafts
    ) {
        Instant now = clock.instant();
        int missing = (int) drafts.stream().filter(item -> item.type() == FindingType.MISSING).count();
        int names = (int) drafts.stream()
                .filter(item -> item.type() == FindingType.FILE_NAME_NONCOMPLIANT).count();
        int cannot = (int) drafts.stream()
                .filter(item -> item.type() == FindingType.CANNOT_CONFIRM).count();
        String reportId = ids.apply("report-");
        ReviewReport report = new ReviewReport(
                reportId,
                job.caseId(),
                job.jobId(),
                request.input().contractVersion(),
                request.input().engineVersion(),
                ReviewReportFreshness.CURRENT,
                drafts.isEmpty() ? ReviewAssessment.M0_READY : ReviewAssessment.M0_NOT_READY,
                request.input().fingerprint().sha256(),
                request.input().checklistSnapshotId(),
                request.input().uploadConfirmationId(),
                request.input().basisRevision(),
                request.input().materialSetRevision(),
                request.input().assignmentRevision(),
                request.input().nameRevision(),
                request.request().baseReportId(),
                missing,
                names,
                cannot,
                now,
                null,
                null);
        List<ReviewFinding> findings = new ArrayList<>();
        List<EvidenceRef> evidence = new ArrayList<>();
        for (FindingDraft draft : drafts) {
            String findingId = ids.apply("finding-");
            findings.add(new ReviewFinding(
                    findingId, reportId, draft.type(), draft.cannotConfirmReason(),
                    draft.requirementId(), draft.materialId(), draft.stableFindingKey(),
                    draft.message(), now));
            for (EvidenceDraft item : draft.evidence()) {
                evidence.add(new EvidenceRef(
                        ids.apply("evidence-"), findingId, item.side(), item.kind(),
                        item.refJson(), item.refDigest(), now));
            }
        }
        return new ReportBundle(report, findings, evidence);
    }
}
