package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.job.JdbcReviewM0JobStore;
import com.javaee.aiservice.review.m0.job.JdbcReviewM0RemoteCallStore;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.OfficialBasis;
import com.javaee.aiservice.review.m0.model.OfficialBasisRole;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.privacy.JdbcRemotePrivacyStore;
import com.javaee.aiservice.review.m0.privacy.RemoteConsentVerifier;
import com.javaee.aiservice.review.m0.store.JdbcReviewCaseStore;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class M003TestFixture {
    public static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final String DIGEST_A = "a".repeat(64);
    public static final String DIGEST_B = "b".repeat(64);

    private final ReviewM0JdbcTestFixture database = new ReviewM0JdbcTestFixture();
    private final AtomicInteger sequence = new AtomicInteger();
    private final M0IdGenerator ids = prefix -> prefix + sequence.incrementAndGet();
    private final JdbcReviewCaseStore caseStore = new JdbcReviewCaseStore(database.jdbc(), database.transactions());
    private final JdbcChecklistStore checklistStore = new JdbcChecklistStore(
            database.jdbc(), database.transactions(), ids);
    private final OfficialBasisService basisService = new OfficialBasisService(
            caseStore, checklistStore, database.transactions(), CLOCK);
    private final ChecklistService checklistService = new ChecklistService(
            caseStore, checklistStore, database.transactions(), ids, CLOCK);
    private final JdbcReviewM0JobStore jobStore = new JdbcReviewM0JobStore(
            database.jdbc(), database.transactions());
    private final JdbcReviewM0RemoteCallStore remoteCallStore = new JdbcReviewM0RemoteCallStore(
            database.jdbc(), database.transactions());
    private final JdbcRemotePrivacyStore privacyStore = new JdbcRemotePrivacyStore(
            database.jdbc(), database.transactions(), ids);
    private final RemoteConsentVerifier consentVerifier = new RemoteConsentVerifier(
            caseStore, jobStore, privacyStore, remoteCallStore, ids);

    public ReviewCase createCase(String caseId, String ownerId, ProcessingMode mode) {
        return caseStore.create(new ReviewCase(
                caseId, ownerId, "Synthetic Review M0", mode,
                0, 0, 0, 0, 0, 0, null, null, NOW, NOW));
    }

    public OfficialBasis attachReadyBasis(
            String ownerId, String caseId, long expectedCaseVersion, String basisId
    ) {
        OfficialBasis attached = basisService.attachOwned(
                ownerId, caseId, expectedCaseVersion,
                new OfficialBasisAttachment(
                        basisId, "file-" + basisId, OfficialBasisRole.APPLICATION_NOTICE,
                        "notice.pdf", "application/pdf", 128, DIGEST_A, "v1"));
        return basisService.markReadyOwned(ownerId, caseId, basisId, attached.version());
    }

    public ChecklistCandidate officialCandidate(String basisId, String displayName) {
        return new ChecklistCandidate(
                displayName, true, RequirementApplicability.APPLIES, 1,
                new FileNameRule(FileNameRuleKind.NONE, null, null),
                List.of(sourceRef(basisId)));
    }

    public ChecklistSourceRef sourceRef(String basisId) {
        return new ChecklistSourceRef(
                basisId,
                new OfficialBasisSourceLocator(null, 1, null, null, "block-1", null),
                "Synthetic official requirement excerpt");
    }

    public ReviewM0JdbcTestFixture database() {
        return database;
    }

    public JdbcReviewCaseStore caseStore() {
        return caseStore;
    }

    public JdbcChecklistStore checklistStore() {
        return checklistStore;
    }

    public OfficialBasisService basisService() {
        return basisService;
    }

    public ChecklistService checklistService() {
        return checklistService;
    }

    public JdbcReviewM0JobStore jobStore() {
        return jobStore;
    }

    public JdbcReviewM0RemoteCallStore remoteCallStore() {
        return remoteCallStore;
    }

    public JdbcRemotePrivacyStore privacyStore() {
        return privacyStore;
    }

    public RemoteConsentVerifier consentVerifier() {
        return consentVerifier;
    }

    public M0IdGenerator ids() {
        return ids;
    }
}
