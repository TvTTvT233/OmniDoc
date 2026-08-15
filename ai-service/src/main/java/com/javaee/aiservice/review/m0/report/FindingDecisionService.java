package com.javaee.aiservice.review.m0.report;

import com.javaee.aiservice.review.m0.model.FindingDecision;
import com.javaee.aiservice.review.m0.model.FindingDecisionType;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Append-only IGNORE event service; it never updates findings, evidence, or immutable reports. */
public final class FindingDecisionService {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final ReviewReportReader reportReader;
    private final Clock clock;

    public FindingDecisionService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ReviewReportReader reportReader,
            Clock clock
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.reportReader = Objects.requireNonNull(reportReader);
        this.clock = Objects.requireNonNull(clock);
    }

    public FindingDecision ignoreOwned(
            String ownerId,
            String caseId,
            String reportId,
            String findingId,
            String reason
    ) {
        if (reason != null && reason.length() > 1024) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID,
                    "Ignore reason exceeds the bounded audit field");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            FindingDecision existing = findDecision(ownerId, caseId, reportId, findingId);
            if (existing != null) return existing;
            List<ReviewReportFreshness> rows = jdbcTemplate.query("""
                            SELECT report.freshness
                            FROM review_m0_report report
                            JOIN review_m0_finding finding ON finding.report_id = report.report_id
                            JOIN review_m0_case review_case ON review_case.case_id = report.case_id
                            WHERE report.report_id = ? AND report.case_id = ? AND finding.finding_id = ?
                              AND review_case.user_id = ?
                            FOR UPDATE
                            """,
                    (rs, row) -> ReviewReportFreshness.valueOf(rs.getString("freshness")),
                    reportId, caseId, findingId, ownerId);
            if (rows.isEmpty()) throw notFound();
            if (rows.get(0) != ReviewReportFreshness.CURRENT) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT,
                        "New finding decisions are allowed only on the current report");
            }
            Instant now = clock.instant();
            FindingDecision decision = new FindingDecision(
                    ReviewM0Ids.next("decision-"), reportId, findingId, FindingDecisionType.IGNORE,
                    reason, ownerId, now);
            try {
                jdbcTemplate.update("""
                                INSERT INTO review_m0_finding_decision(
                                    decision_id, report_id, finding_id, decision_type,
                                    reason, decided_by, decided_at
                                ) VALUES (?, ?, ?, 'IGNORE', ?, ?, ?)
                                """,
                        decision.decisionId(), reportId, findingId, reason, ownerId, Timestamp.from(now));
                return decision;
            } catch (DataIntegrityViolationException duplicate) {
                FindingDecision replay = findDecision(ownerId, caseId, reportId, findingId);
                if (replay != null) return replay;
                throw duplicate;
            }
        }));
    }

    public EffectiveReportView getEffectiveOwned(String ownerId, String caseId, String reportId) {
        PublishedReview published = reportReader.requireOwned(ownerId, caseId, reportId);
        List<FindingDecision> decisions = listDecisions(ownerId, caseId, reportId);
        int ignoredCount = (int) decisions.stream()
                .filter(item -> item.decisionType() == FindingDecisionType.IGNORE)
                .map(FindingDecision::findingId)
                .distinct()
                .count();
        ReviewAssessment effective = ignoredCount == 0
                && published.report().freshness() == ReviewReportFreshness.CURRENT
                && published.report().assessment() == ReviewAssessment.M0_READY
                ? ReviewAssessment.M0_READY : ReviewAssessment.M0_NOT_READY;
        return new EffectiveReportView(published, decisions, ignoredCount, effective);
    }

    public List<FindingDecision> listDecisions(String ownerId, String caseId, String reportId) {
        reportReader.requireOwned(ownerId, caseId, reportId);
        return jdbcTemplate.query("""
                        SELECT decision.decision_id, decision.report_id, decision.finding_id,
                               decision.decision_type, decision.reason, decision.decided_by, decision.decided_at
                        FROM review_m0_finding_decision decision
                        JOIN review_m0_report report ON report.report_id = decision.report_id
                        JOIN review_m0_case review_case ON review_case.case_id = report.case_id
                        WHERE decision.report_id = ? AND report.case_id = ? AND review_case.user_id = ?
                        ORDER BY decision.decided_at, decision.decision_id
                        """,
                (rs, row) -> new FindingDecision(
                        rs.getString("decision_id"), rs.getString("report_id"),
                        rs.getString("finding_id"), FindingDecisionType.valueOf(rs.getString("decision_type")),
                        rs.getString("reason"), rs.getString("decided_by"),
                        rs.getTimestamp("decided_at").toInstant()),
                reportId, caseId, ownerId);
    }

    private FindingDecision findDecision(
            String ownerId, String caseId, String reportId, String findingId
    ) {
        return jdbcTemplate.query("""
                        SELECT decision.decision_id, decision.report_id, decision.finding_id,
                               decision.decision_type, decision.reason, decision.decided_by, decision.decided_at
                        FROM review_m0_finding_decision decision
                        JOIN review_m0_report report ON report.report_id = decision.report_id
                        JOIN review_m0_case review_case ON review_case.case_id = report.case_id
                        WHERE decision.report_id = ? AND decision.finding_id = ?
                          AND report.case_id = ? AND review_case.user_id = ?
                        """,
                (rs, row) -> new FindingDecision(
                        rs.getString("decision_id"), rs.getString("report_id"),
                        rs.getString("finding_id"), FindingDecisionType.valueOf(rs.getString("decision_type")),
                        rs.getString("reason"), rs.getString("decided_by"),
                        rs.getTimestamp("decided_at").toInstant()),
                reportId, findingId, caseId, ownerId).stream().findFirst().orElse(null);
    }

    private static ReviewM0Exception notFound() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                "Report finding was not found");
    }
}
