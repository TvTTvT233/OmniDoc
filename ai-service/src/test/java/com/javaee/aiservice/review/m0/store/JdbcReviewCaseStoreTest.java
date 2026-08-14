package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.RevisionKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData.DIGEST_A;
import static com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData.DIGEST_B;
import static com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT;
import static com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReviewCaseStoreTest {
    @Test
    void allOwnedReadsUseOwnerPredicateAndCrossOwnerLooksAbsent() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewCase created = ReviewM0StoreTestData.createCase(fixture, "case-owner", "owner-a");
        JdbcReviewCaseStore store = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());

        assertThat(store.findOwned("owner-a", created.caseId())).contains(created);
        assertThat(store.findOwned("owner-b", created.caseId())).isEmpty();
        assertThatThrownBy(() -> store.advanceRevisionOwned(
                "owner-b", created.caseId(), 0, RevisionKind.NAME, "NAME_CHANGED"))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
    }

    @Test
    void staleCasAllowsOnlyOneConcurrentRevisionAdvance() throws Exception {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-cas", "owner");
        JdbcReviewCaseStore store = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> update = () -> {
            start.await();
            try {
                store.advanceRevisionOwned("owner", "case-cas", 0, RevisionKind.NAME, "NAME_CHANGED");
                return true;
            } catch (ReviewM0Exception error) {
                assertThat(error.errorCode()).isEqualTo(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT);
                return false;
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(update);
            var second = executor.submit(update);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
        ReviewCase current = store.findOwned("owner", "case-cas").orElseThrow();
        assertThat(current.nameRevision()).isEqualTo(1);
        assertThat(current.version()).isEqualTo(1);
    }

    @Test
    void callerTransactionRollbackAlsoRollsBackRevisionAndOutdate() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-rollback", "owner");
        JdbcReviewCaseStore store = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());
        TransactionTemplate outer = new TransactionTemplate(fixture.transactions());

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            store.advanceRevisionOwned("owner", "case-rollback", 0, RevisionKind.ASSIGNMENT, "ASSIGNMENT_CHANGED");
            throw new IllegalStateException("synthetic failure after mutation");
        })).isInstanceOf(IllegalStateException.class);

        ReviewCase unchanged = store.findOwned("owner", "case-rollback").orElseThrow();
        assertThat(unchanged.assignmentRevision()).isZero();
        assertThat(unchanged.version()).isZero();
    }

    @ParameterizedTest
    @EnumSource(RevisionKind.class)
    void everyFrozenRevisionOrEngineChangeExpiresCurrentReportInTheSameMutation(RevisionKind kind) {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-" + kind, "owner");
        ReviewM0StoreTestData.publishSnapshot(fixture, "case-" + kind, "owner", 0);
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-" + kind, "case-" + kind, "owner",
                com.javaee.aiservice.review.m0.model.ReviewJobType.FORMAL_REVIEW,
                "idem-" + kind, "worker");
        ReviewM0StoreTestData.publishReadyReport(
                fixture, "case-" + kind, "owner", "job-" + kind, "worker",
                Integer.toHexString(kind.ordinal()).repeat(64).substring(0, 64));
        JdbcReviewCaseStore store = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());

        ReviewCase before = store.findOwned("owner", "case-" + kind).orElseThrow();
        ReviewCase after = store.advanceRevisionOwned(
                "owner", before.caseId(), before.version(), kind, kind.name() + "_CHANGED");

        assertThat(after.currentReportId()).isNull();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT freshness FROM review_m0_report WHERE report_id = ?",
                String.class, "report-job-" + kind)).isEqualTo("OUTDATED");
        if (kind == RevisionKind.BASIS) {
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT lifecycle FROM review_m0_checklist_snapshot WHERE checklist_snapshot_id = ?",
                    String.class, "clsnap-case-" + kind)).isEqualTo("STALE_BASIS");
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT lifecycle FROM review_m0_checklist_revision WHERE checklist_revision_id = ?",
                    String.class, "clrev-case-" + kind)).isEqualTo("STALE_BASIS");
        }
    }

    @Test
    void publishingNewFormalChecklistSupersedesOldSnapshotAndExpiresReportAtomically() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-checklist", "owner");
        ReviewM0StoreTestData.publishSnapshot(fixture, "case-checklist", "owner", 0);
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-checklist", "case-checklist", "owner",
                com.javaee.aiservice.review.m0.model.ReviewJobType.FORMAL_REVIEW,
                "idem-checklist", "worker");
        ReviewM0StoreTestData.publishReadyReport(
                fixture, "case-checklist", "owner", "job-checklist", "worker", "c".repeat(64));
        fixture.jdbc().update("""
                        INSERT INTO review_m0_checklist_revision(
                            checklist_revision_id, case_id, lifecycle, basis_revision, revision_number,
                            draft_digest, extraction_job_id, created_by, created_at
                        ) VALUES ('clrev-next', 'case-checklist', 'CANDIDATE_DRAFT', 0, 2, ?, NULL, 'owner', ?)
                        """,
                DIGEST_A, JdbcReviewCaseStore.timestamp(T0.plusSeconds(6)));
        ChecklistSnapshot next = new ChecklistSnapshot(
                "clsnap-next", "case-checklist", "clrev-next", 0, 2, "{}", DIGEST_EMPTY_OBJECT,
                ChecklistLifecycle.CONFIRMED, "owner", T0.plusSeconds(7), null);
        JdbcReviewCaseStore store = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());
        ReviewCase current = store.findOwned("owner", "case-checklist").orElseThrow();

        store.publishChecklistSnapshotOwned(
                "owner", "case-checklist", current.version(), next, "CHECKLIST_PUBLISHED");

        assertThat(fixture.jdbc().queryForObject(
                "SELECT lifecycle FROM review_m0_checklist_snapshot WHERE checklist_snapshot_id = 'clsnap-case-checklist'",
                String.class)).isEqualTo("SUPERSEDED");
        assertThat(store.findOwned("owner", "case-checklist").orElseThrow().currentReportId()).isNull();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT freshness FROM review_m0_report WHERE report_id = 'report-job-checklist'",
                String.class)).isEqualTo("OUTDATED");
    }

    @Test
    void immutableChecklistSnapshotReadRejectsNonCanonicalOrDigestMismatchedJson() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-integrity", "owner");
        ReviewM0StoreTestData.publishSnapshot(fixture, "case-integrity", "owner", 0);
        JdbcReviewCaseStore store = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());
        assertThat(store.findChecklistSnapshotOwned(
                "owner", "case-integrity", "clsnap-case-integrity")).isPresent();

        fixture.jdbc().update("""
                        UPDATE review_m0_checklist_snapshot SET snapshot_json = ' { } '
                        WHERE checklist_snapshot_id = 'clsnap-case-integrity'
                        """);
        assertThatThrownBy(() -> store.findChecklistSnapshotOwned(
                "owner", "case-integrity", "clsnap-case-integrity"))
                .isInstanceOf(ReviewM0Exception.class)
                .hasMessageContaining("not canonical");
    }
}
