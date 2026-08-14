package com.javaee.aiservice.review.m0.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public final class ReviewM0StateMachines {
    private static final Map<OfficialBasisStatus, EnumSet<OfficialBasisStatus>> BASIS =
            new EnumMap<>(OfficialBasisStatus.class);
    private static final Map<ChecklistLifecycle, EnumSet<ChecklistLifecycle>> CHECKLIST =
            new EnumMap<>(ChecklistLifecycle.class);
    private static final Map<ReviewJobStatus, EnumSet<ReviewJobStatus>> JOB =
            new EnumMap<>(ReviewJobStatus.class);

    static {
        BASIS.put(OfficialBasisStatus.ATTACHED,
                EnumSet.of(OfficialBasisStatus.READY, OfficialBasisStatus.CANNOT_CONFIRM,
                        OfficialBasisStatus.DETACHED));
        BASIS.put(OfficialBasisStatus.READY, EnumSet.of(OfficialBasisStatus.DETACHED));
        BASIS.put(OfficialBasisStatus.CANNOT_CONFIRM, EnumSet.of(OfficialBasisStatus.DETACHED));

        CHECKLIST.put(ChecklistLifecycle.CANDIDATE_DRAFT, EnumSet.of(ChecklistLifecycle.CONFIRMED));
        CHECKLIST.put(ChecklistLifecycle.CONFIRMED,
                EnumSet.of(ChecklistLifecycle.STALE_BASIS, ChecklistLifecycle.SUPERSEDED));

        JOB.put(ReviewJobStatus.QUEUED,
                EnumSet.of(ReviewJobStatus.RUNNING, ReviewJobStatus.CANCEL_REQUESTED));
        JOB.put(ReviewJobStatus.RUNNING,
                EnumSet.of(ReviewJobStatus.SUCCEEDED, ReviewJobStatus.FAILED, ReviewJobStatus.CANCEL_REQUESTED));
        JOB.put(ReviewJobStatus.CANCEL_REQUESTED, EnumSet.of(ReviewJobStatus.CANCELLED));
    }

    private ReviewM0StateMachines() {
    }

    public static void requireOfficialBasisTransition(OfficialBasisStatus from, OfficialBasisStatus to) {
        requireTransition(BASIS, from, to, "official basis", ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT);
    }

    public static void requireChecklistTransition(ChecklistLifecycle from, ChecklistLifecycle to) {
        requireTransition(CHECKLIST, from, to, "checklist", ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT);
    }

    public static void requireJobTransition(ReviewJobStatus from, ReviewJobStatus to) {
        requireTransition(JOB, from, to, "job", ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT);
    }

    public static void requireFreshnessTransition(ReviewReportFreshness from, ReviewReportFreshness to) {
        if (from == to) {
            return;
        }
        if (from != ReviewReportFreshness.CURRENT || to != ReviewReportFreshness.OUTDATED) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT,
                    "Report freshness cannot transition from " + from + " to " + to);
        }
    }

    public static void requireFormalChecklist(ChecklistLifecycle lifecycle) {
        if (lifecycle != ChecklistLifecycle.CONFIRMED) {
            throw new ReviewM0Exception(
                    lifecycle == ChecklistLifecycle.STALE_BASIS
                            ? ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_STALE_BASIS
                            : ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED,
                    "Formal review requires the current CONFIRMED checklist snapshot");
        }
    }

    public static void requireReadyProjection(ReviewReport report, int ignoredCount) {
        if (report.assessment() == ReviewAssessment.M0_READY
                && (ignoredCount != 0 || report.freshness() != ReviewReportFreshness.CURRENT
                || report.missingCount() != 0 || report.fileNameNoncompliantCount() != 0
                || report.cannotConfirmCount() != 0)) {
            throw ModelChecks.invalid("M0_READY cannot contain findings, ignored decisions, or stale input");
        }
    }

    private static <T extends Enum<T>> void requireTransition(
            Map<T, EnumSet<T>> transitions, T from, T to, String aggregate, ReviewM0ErrorCode errorCode
    ) {
        if (from == to) {
            return;
        }
        if (!transitions.getOrDefault(from, EnumSet.noneOf(from.getDeclaringClass())).contains(to)) {
            throw new ReviewM0Exception(
                    errorCode,
                    "Illegal " + aggregate + " transition from " + from + " to " + to);
        }
    }
}
