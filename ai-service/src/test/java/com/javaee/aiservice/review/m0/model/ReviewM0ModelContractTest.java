package com.javaee.aiservice.review.m0.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewM0ModelContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void everyFrozenEnumRoundTripsUsingItsExactName() throws Exception {
        List<Class<? extends Enum<?>>> enumTypes = List.of(
                ProcessingMode.class, OfficialBasisRole.class, OfficialBasisStatus.class,
                ChecklistLifecycle.class, RequirementOrigin.class, RequirementAuthorship.class,
                RequirementApplicability.class, FileNameRuleKind.class, FormalFileType.class,
                MaterialKind.class, SubmissionSourceStatus.class, MaterialStatus.class,
                AssignmentStatus.class, ReviewJobType.class, ReviewMode.class, ReviewJobStatus.class,
                ReviewReportFreshness.class, ReviewAssessment.class, FindingType.class,
                CannotConfirmReason.class, DiagnosticSubjectType.class, FindingDecisionType.class,
                EvidenceKind.class, RemotePreviewStatus.class, RemoteCallState.class, IdempotencyState.class);

        for (Class<? extends Enum<?>> type : enumTypes) {
            for (Enum<?> value : type.getEnumConstants()) {
                String json = objectMapper.writeValueAsString(value);
                assertThat(json).isEqualTo('"' + value.name() + '"');
                assertThat(objectMapper.readValue(json, type)).isEqualTo(value);
            }
        }
    }

    @Test
    void fileNameRulesRequireExplicitCaseSensitivityAndNoneRequiresNulls() {
        assertThat(new FileNameRule(FileNameRuleKind.EXACT, "申请表.pdf", true).caseSensitive()).isTrue();
        assertThat(new FileNameRule(FileNameRuleKind.NONE, null, null).kind()).isEqualTo(FileNameRuleKind.NONE);

        assertThatThrownBy(() -> new FileNameRule(FileNameRuleKind.EXACT, "申请表.pdf", null))
                .isInstanceOf(ReviewM0Exception.class);
        assertThatThrownBy(() -> new FileNameRule(FileNameRuleKind.NONE, "ignored", false))
                .isInstanceOf(ReviewM0Exception.class);
        assertThatThrownBy(() -> new FileNameRule(FileNameRuleKind.REGEX, "[", true))
                .isInstanceOf(ReviewM0Exception.class);
    }

    @Test
    void logicalFileNamesAreNfcLeafNamesAndMustMatchTheActualType() {
        LogicalFileName normalized = new LogicalFileName("A\u030A.pdf");
        assertThat(normalized.normalized()).isEqualTo("Å.pdf");
        normalized.requireType(FormalFileType.PDF);

        assertThatThrownBy(() -> new LogicalFileName("../escape.pdf"))
                .isInstanceOf(ReviewM0Exception.class);
        assertThatThrownBy(() -> new LogicalFileName("header\r\n.pdf"))
                .isInstanceOf(ReviewM0Exception.class);
        assertThatThrownBy(() -> normalized.requireType(FormalFileType.DOCX))
                .isInstanceOf(ReviewM0Exception.class);
        assertThat(FormalFileType.fromFileName("FORM.PDF")).contains(FormalFileType.PDF);
        assertThat(FormalFileType.fromFileName("scan.jpeg")).isEmpty();
    }

    @Test
    void zipRemainsAFormalInputContainerButCannotBecomeAMaterialUnit() {
        assertThat(FormalFileType.fromFileName("submission.ZIP")).contains(FormalFileType.ZIP);
        assertThat(FormalFileType.ZIP.isLeafType()).isFalse();

        SubmissionSource container = new SubmissionSource(
                "source-zip", "case", "file-zip", "submission.zip", "application/zip",
                FormalFileType.ZIP, SubmissionSourceStatus.ATTACHED, 128, "a".repeat(64), null,
                1, null, 0, Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-14T00:00:00Z"));
        assertThat(container.formalFileType()).isEqualTo(FormalFileType.ZIP);

        assertThatThrownBy(() -> new SubmissionMaterial(
                "material-zip", "case", container.sourceId(), MaterialKind.TOP_LEVEL_FILE,
                null, null, null, "b".repeat(64), FormalFileType.ZIP,
                "submission.zip", "submission.zip", MaterialStatus.AVAILABLE, null, null,
                1, 0, 0, Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-14T00:00:00Z")))
                .isInstanceOf(ReviewM0Exception.class)
                .hasMessageContaining("cannot be persisted as material");
    }

    @Test
    void illegalAggregateTransitionsAndCandidateFormalReviewAreRejected() {
        ReviewM0StateMachines.requireOfficialBasisTransition(OfficialBasisStatus.ATTACHED, OfficialBasisStatus.READY);
        ReviewM0StateMachines.requireJobTransition(ReviewJobStatus.RUNNING, ReviewJobStatus.CANCEL_REQUESTED);
        ReviewM0StateMachines.requireFreshnessTransition(
                ReviewReportFreshness.CURRENT, ReviewReportFreshness.OUTDATED);

        assertThatThrownBy(() -> ReviewM0StateMachines.requireJobTransition(
                ReviewJobStatus.SUCCEEDED, ReviewJobStatus.RUNNING))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT);
        assertThatThrownBy(() -> ReviewM0StateMachines.requireFreshnessTransition(
                ReviewReportFreshness.OUTDATED, ReviewReportFreshness.CURRENT))
                .isInstanceOf(ReviewM0Exception.class);
        assertThatThrownBy(() -> ReviewM0StateMachines.requireFormalChecklist(ChecklistLifecycle.CANDIDATE_DRAFT))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED);
    }

    @Test
    void readyProjectionCannotContainIgnoredOrOtherBlockingFindings() {
        ReviewReport ready = new ReviewReport(
                "report", "case", "job", "REVIEW_M0_CONTRACT_V1", "engine-v1",
                ReviewReportFreshness.CURRENT, ReviewAssessment.M0_READY, "a".repeat(64),
                "snapshot", "upload", 1, 1, 1, 1, null, 0, 0, 0,
                Instant.parse("2026-08-14T00:00:00Z"), null, null);
        ReviewM0StateMachines.requireReadyProjection(ready, 0);
        assertThatThrownBy(() -> ReviewM0StateMachines.requireReadyProjection(ready, 1))
                .isInstanceOf(ReviewM0Exception.class);
    }

    @Test
    void fingerprintIsIndependentOfInsertionOrderAndStableAcrossProcesses() throws Exception {
        ReviewInputFingerprint first = ReviewInputFingerprint.calculate(fingerprintSpec(false));
        ReviewInputFingerprint reordered = ReviewInputFingerprint.calculate(fingerprintSpec(true));
        assertThat(reordered).isEqualTo(first);

        Process process = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-cp", System.getProperty("java.class.path"),
                FingerprintProbe.class.getName())
                .redirectErrorStream(true)
                .start();
        String childDigest = new String(process.getInputStream().readAllBytes()).trim();
        assertThat(process.waitFor()).isZero();
        assertThat(childDigest).isEqualTo(first.sha256());
        assertThat(first.canonicalJson()).doesNotContain(" ", "\n", "\r");
    }

    static ReviewInputFingerprintSpec fingerprintSpec(boolean reverse) {
        List<DigestEntry> bases = Arrays.asList(
                new DigestEntry("basis-😀", "1".repeat(64)),
                new DigestEntry("basis-a", "2".repeat(64)));
        List<DigestEntry> materials = Arrays.asList(
                new DigestEntry("mat-z", "3".repeat(64)),
                new DigestEntry("mat-a", "4".repeat(64)));
        List<ConfirmedAssignmentFingerprint> assignments = Arrays.asList(
                new ConfirmedAssignmentFingerprint("mat-z", "req-b"),
                new ConfirmedAssignmentFingerprint("mat-a", "req-a"));
        List<LogicalNameFingerprint> names = Arrays.asList(
                new LogicalNameFingerprint("mat-z", "证明.PNG"),
                new LogicalNameFingerprint("mat-a", "申请表.pdf"));
        if (reverse) {
            Collections.reverse(bases);
            Collections.reverse(materials);
            Collections.reverse(assignments);
            Collections.reverse(names);
        }
        return new ReviewInputFingerprintSpec(
                "REVIEW_M0_CONTRACT_V1", "case-1", 2, bases,
                "snapshot-1", "5".repeat(64), 3, materials,
                4, assignments, 5, names, "upload-1", "engine-v1");
    }
}
