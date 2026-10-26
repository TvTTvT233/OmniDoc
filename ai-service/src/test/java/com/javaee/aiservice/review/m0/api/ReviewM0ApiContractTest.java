package com.javaee.aiservice.review.m0.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.archive.ZipArchiveEntryOpener;
import com.javaee.aiservice.review.m0.config.ReviewM0Configuration;
import com.javaee.aiservice.review.m0.checklist.JdbcChecklistStore;
import com.javaee.aiservice.review.m0.checklist.OfficialBasisService;
import com.javaee.aiservice.review.m0.file.*;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.job.*;
import com.javaee.aiservice.review.m0.material.JdbcReviewM0MaterialRepository;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewSegment;
import com.javaee.aiservice.review.m0.store.*;
import com.javaee.aiservice.security.RequestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewM0ApiContractTest {
    private static final String OWNER = "m006-owner";
    private static final String OTHER = "m006-other";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private AnnotationConfigApplicationContext context;
    private MockMvc mvc;
    private InMemoryOwnedFileByteSource files;

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @Test
    void controllerExposesOnlyTheThirtyFrozenMethodPathPairs() {
        Set<String> actual = new TreeSet<>();
        RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(ReviewM0Controller.class, RequestMapping.class);
        assertThat(base).isNotNull();
        for (Method method : ReviewM0Controller.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) continue;
            for (var http : mapping.method()) for (String path : mapping.path()) {
                actual.add(http.name() + " " + base.path()[0] + path);
            }
        }
        assertThat(actual).containsExactlyElementsOf(new TreeSet<>(List.of(
                "POST /api/review-m0/cases",
                "GET /api/review-m0/cases/{caseId}",
                "POST /api/review-m0/cases/{caseId}/official-bases",
                "DELETE /api/review-m0/cases/{caseId}/official-bases/{basisId}",
                "GET /api/review-m0/cases/{caseId}/official-bases",
                "PATCH /api/review-m0/cases/{caseId}/official-bases/{basisId}",
                "POST /api/review-m0/cases/{caseId}/remote-previews",
                "PATCH /api/review-m0/cases/{caseId}/remote-previews/{previewId}",
                "POST /api/review-m0/cases/{caseId}/remote-previews/{previewId}/confirm",
                "POST /api/review-m0/cases/{caseId}/checklist-extractions",
                "GET /api/review-m0/cases/{caseId}/checklist-draft",
                "POST /api/review-m0/cases/{caseId}/checklist-draft/requirements",
                "PATCH /api/review-m0/cases/{caseId}/checklist-draft/requirements/{requirementId}",
                "POST /api/review-m0/cases/{caseId}/checklist-snapshots",
                "GET /api/review-m0/cases/{caseId}/checklist-snapshots/{checklistSnapshotId}",
                "POST /api/review-m0/cases/{caseId}/submission-sources",
                "DELETE /api/review-m0/cases/{caseId}/submission-sources/{sourceId}",
                "GET /api/review-m0/cases/{caseId}/submission-sources",
                "GET /api/review-m0/cases/{caseId}/materials",
                "PATCH /api/review-m0/cases/{caseId}/materials/{materialId}",
                "PUT /api/review-m0/cases/{caseId}/assignments",
                "GET /api/review-m0/cases/{caseId}/assignments",
                "POST /api/review-m0/cases/{caseId}/upload-confirmations",
                "GET /api/review-m0/cases/{caseId}/materials/{materialId}/download",
                "POST /api/review-m0/cases/{caseId}/reviews",
                "GET /api/review-m0/cases/{caseId}/reports/current",
                "GET /api/review-m0/cases/{caseId}/reports/{reportId}",
                "POST /api/review-m0/cases/{caseId}/reports/{reportId}/findings/{findingId}/decisions",
                "GET /api/review-m0/cases/{caseId}/jobs/{jobId}",
                "POST /api/review-m0/cases/{caseId}/jobs/{jobId}/cancel")));
    }

    @Test
    void everyFrozenEndpointRejectsUnauthenticatedAccessWith401() throws Exception {
        setup(false);
        SecurityContextHolder.clearContext();
        for (MockHttpServletRequestBuilder request : unauthenticatedRequests()) {
            assertError(mvc.perform(request), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void caseContractUsesExactShapeAndDurableIdempotency() throws Exception {
        setup(false);
        authenticate(OWNER);
        String body = "{\"title\":\"2026 scholarship\",\"processingMode\":\"LOCAL_ONLY\"}";
        MvcResult first = mvc.perform(post("/api/review-m0/cases")
                        .header("Idempotency-Key", "case-key").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        context.getBean(JdbcTemplate.class).update("""
                UPDATE review_m0_case SET title='mutated-after-first-response', version=version+1,
                    updated_at=DATEADD('SECOND', 1, updated_at)
                """);
        MvcResult replay = mvc.perform(post("/api/review-m0/cases")
                        .header("Idempotency-Key", "case-key").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode response = json.readTree(first.getResponse().getContentAsString());
        assertThat(fieldNames(response)).containsExactlyInAnyOrder(
                "caseId", "title", "processingMode", "basisRevision", "checklistRevision",
                "materialSetRevision", "assignmentRevision", "nameRevision", "version",
                "currentChecklistSnapshotId", "currentReportId", "createdAt", "updatedAt");
        assertThat(response.has("userId")).isFalse();
        assertThat(json.readTree(replay.getResponse().getContentAsString())).isEqualTo(response);
        mvc.perform(get("/api/review-m0/cases/{caseId}", response.path("caseId").textValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("mutated-after-first-response"));
        assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT COUNT(*) FROM review_m0_case", Integer.class)).isEqualTo(1);
    }

    @Test
    void unknownFieldsBadEnumsMissingKeyAndChangedReplayAreFourField400Or409() throws Exception {
        setup(false);
        authenticate(OWNER);
        assertError(mvc.perform(post("/api/review-m0/cases").header("Idempotency-Key", "unknown")
                        .contentType("application/json")
                        .content("{\"title\":\"x\",\"processingMode\":\"LOCAL_ONLY\",\"userId\":\"attack\"}")),
                400, "REVIEW_M0_REQUEST_INVALID");
        assertError(mvc.perform(post("/api/review-m0/cases").header("Idempotency-Key", "enum")
                        .contentType("application/json")
                        .content("{\"title\":\"x\",\"processingMode\":\"local_only\"}")),
                400, "REVIEW_M0_REQUEST_INVALID");
        assertError(mvc.perform(post("/api/review-m0/cases").contentType("application/json")
                        .content("{\"title\":\"x\",\"processingMode\":\"LOCAL_ONLY\"}")),
                400, "REVIEW_M0_REQUEST_INVALID");
        createCase("same", "first");
        assertError(mvc.perform(post("/api/review-m0/cases").header("Idempotency-Key", "same")
                        .contentType("application/json")
                        .content("{\"title\":\"second\",\"processingMode\":\"LOCAL_ONLY\"}")),
                409, "REVIEW_M0_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void authenticationAndOwnerIsolationHaveNoSuccessSideChannel() throws Exception {
        setup(false);
        SecurityContextHolder.clearContext();
        assertError(mvc.perform(get("/api/review-m0/cases/case_missing")),
                401, "AUTHENTICATION_REQUIRED");
        authenticate(OWNER);
        String caseId = createCase("owned", "owned");
        authenticate(OTHER);
        assertError(mvc.perform(get("/api/review-m0/cases/{caseId}", caseId)),
                404, "REVIEW_M0_CASE_NOT_FOUND");
        assertError(mvc.perform(get("/api/review-m0/cases/case_missing")),
                404, "REVIEW_M0_CASE_NOT_FOUND");
    }

    @Test
    void currentReportMissingUsesFrozenReportDetail() throws Exception {
        setup(false);
        authenticate(OWNER);
        String caseId = createCase("report", "report");
        MvcResult result = assertError(mvc.perform(get(
                        "/api/review-m0/cases/{caseId}/reports/current", caseId)),
                404, "REVIEW_M0_RESOURCE_NOT_FOUND");
        assertThat(json.readTree(result.getResponse().getContentAsString())
                .path("details").path("resourceType").textValue()).isEqualTo("REPORT");
    }

    @Test
    void productionFileDependencyIs503AndDoesNotOccupyIdempotencyKey() throws Exception {
        setup(false);
        authenticate(OWNER);
        String caseId = createCase("file", "file");
        String body = "{\"fileId\":\"file-a\",\"role\":\"APPLICATION_NOTICE\"}";
        for (int index = 0; index < 2; index++) {
            assertError(mvc.perform(post("/api/review-m0/cases/{caseId}/official-bases", caseId)
                            .header("Idempotency-Key", "file-key").contentType("application/json").content(body)),
                    503, "REVIEW_M0_FILE_SERVICE_UNAVAILABLE");
        }
        assertThat(context.getBean(JdbcTemplate.class).queryForObject("""
                SELECT COUNT(*) FROM review_m0_idempotency
                WHERE canonical_path LIKE '%/official-bases'
                """, Integer.class)).isZero();
        assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isZero();
    }

    @Test
    void officialBasisAndRemotePrivacyEndpointsUseOwnerCasAndOneTimeConsent() throws Exception {
        setup(true);
        authenticate(OWNER);
        byte[] bytes = SyntheticFileFixtures.pdf("basis");
        files.put(OWNER, "file-basis", "notice.pdf", "application/pdf", bytes, "v1", "private/basis");
        String caseId = createCase("remote-case", "remote", "REMOTE_MODEL");
        MvcResult attachedResult = mvc.perform(post("/api/review-m0/cases/{caseId}/official-bases", caseId)
                        .header("Idempotency-Key", "basis-attach").contentType("application/json")
                        .content("{\"fileId\":\"file-basis\",\"role\":\"APPLICATION_NOTICE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.basisRevision").value(1)).andReturn();
        String basisId = json.readTree(attachedResult.getResponse().getContentAsString()).path("basisId").textValue();
        context.getBean(OfficialBasisService.class).markReadyOwned(OWNER, caseId, basisId, 0);
        mvc.perform(get("/api/review-m0/cases/{caseId}/official-bases", caseId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("READY"));
        MvcResult reclassified = mvc.perform(patch(
                        "/api/review-m0/cases/{caseId}/official-bases/{basisId}", caseId, basisId)
                        .header("Idempotency-Key", "basis-role").contentType("application/json")
                        .content("{\"expectedVersion\":1,\"role\":\"FILLING_GUIDE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.basisRevision").value(2)).andReturn();
        assertError(mvc.perform(patch("/api/review-m0/cases/{caseId}/official-bases/{basisId}", caseId, basisId)
                        .header("Idempotency-Key", "basis-role-stale").contentType("application/json")
                        .content("{\"expectedVersion\":1,\"role\":\"APPLICATION_NOTICE\"}")),
                409, "REVIEW_M0_VERSION_CONFLICT");
        MvcResult previewResult = mvc.perform(post("/api/review-m0/cases/{caseId}/remote-previews", caseId)
                        .header("Idempotency-Key", "preview").contentType("application/json")
                        .content("{\"operation\":\"CHECKLIST_EXTRACTION\",\"basisRevision\":2}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.segments[0].basisId").value(basisId)).andReturn();
        JsonNode preview = json.readTree(previewResult.getResponse().getContentAsString());
        String previewId = preview.path("previewId").textValue();
        String segmentId = preview.path("segments").get(0).path("segmentId").textValue();
        MvcResult edited = mvc.perform(patch(
                        "/api/review-m0/cases/{caseId}/remote-previews/{previewId}", caseId, previewId)
                        .header("Idempotency-Key", "preview-edit").contentType("application/json")
                        .content("{\"expectedVersion\":0,\"segments\":[{\"segmentId\":\"" + segmentId
                                + "\",\"included\":true,\"redactedText\":\"redacted requirement\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_CONFIRMATION"))
                .andReturn();
        String digest = json.readTree(edited.getResponse().getContentAsString()).path("payloadDigest").textValue();
        mvc.perform(post("/api/review-m0/cases/{caseId}/remote-previews/{previewId}/confirm", caseId, previewId)
                        .header("Idempotency-Key", "preview-confirm").contentType("application/json")
                        .content("{\"expectedVersion\":1,\"acknowledgedPayloadDigest\":\"" + digest + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.consumedByJobId").isEmpty());
        mvc.perform(delete("/api/review-m0/cases/{caseId}/official-bases/{basisId}", caseId, basisId)
                        .header("Idempotency-Key", "basis-delete").contentType("application/json")
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DETACHED"));
    }

    @Test
    void extractionJobQueryCancellationRecoveryAndSystemFailureRemainBusinessFactFree() throws Exception {
        setup(false);
        authenticate(OWNER);
        String caseId = createCase("jobs", "jobs");
        String extraction = "{\"basisRevision\":0,\"processingMode\":\"LOCAL_ONLY\",\"consentId\":null}";
        MvcResult submitted = mvc.perform(post("/api/review-m0/cases/{caseId}/checklist-extractions", caseId)
                        .header("Idempotency-Key", "extract-cancel").contentType("application/json").content(extraction))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED")).andReturn();
        String jobId = json.readTree(submitted.getResponse().getContentAsString()).path("jobId").textValue();
        context.getBean(JdbcTemplate.class).update(
                "UPDATE review_m0_job SET progress_total=9, version=version+1 WHERE job_id=?", jobId);
        MvcResult replay = mvc.perform(post("/api/review-m0/cases/{caseId}/checklist-extractions", caseId)
                        .header("Idempotency-Key", "extract-cancel").contentType("application/json").content(extraction))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value(jobId)).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()))
                .isEqualTo(json.readTree(submitted.getResponse().getContentAsString()));
        mvc.perform(get("/api/review-m0/cases/{caseId}/jobs/{jobId}", caseId, jobId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("CHECKLIST_EXTRACTION"))
                .andExpect(jsonPath("$.progress.totalUnits").value(9));
        mvc.perform(post("/api/review-m0/cases/{caseId}/jobs/{jobId}/cancel", caseId, jobId)
                        .header("Idempotency-Key", "cancel"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));
        context.getBean(ReviewM0JobStore.class).finalizeCancellation(
                jobId, "recovery-worker", Instant.now());
        mvc.perform(post("/api/review-m0/cases/{caseId}/jobs/{jobId}/cancel", caseId, jobId)
                        .header("Idempotency-Key", "cancel-terminal"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        MvcResult failing = mvc.perform(post("/api/review-m0/cases/{caseId}/checklist-extractions", caseId)
                        .header("Idempotency-Key", "extract-fail").contentType("application/json").content(extraction))
                .andExpect(status().isAccepted()).andReturn();
        String failingJobId = json.readTree(failing.getResponse().getContentAsString()).path("jobId").textValue();
        context.getBean(ReviewM0JobDispatcher.class).runNext(
                "failure-worker", Instant.now(), Duration.ofMinutes(1), 1);
        mvc.perform(get("/api/review-m0/cases/{caseId}/jobs/{jobId}", caseId, failingJobId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.errorCode").value("REVIEW_M0_FILE_SERVICE_UNAVAILABLE"));
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isZero();
    }

    @Test
    void checklistPublicationAndFormalGateRejectMissingUploadWithoutCreatingJobOrFinding() throws Exception {
        setup(false);
        authenticate(OWNER);
        String caseId = createCase("gate", "gate");
        MvcResult draftResult = mvc.perform(post(
                        "/api/review-m0/cases/{caseId}/checklist-draft/requirements", caseId)
                        .header("Idempotency-Key", "requirement").contentType("application/json")
                        .content("""
                                {"displayName":"Application form","enabled":true,"applicability":"APPLIES",
                                 "minimumCount":1,"fileNameRule":{"kind":"NONE","pattern":null,"caseSensitive":null}}
                                """))
                .andExpect(status().isCreated()).andReturn();
        JsonNode draft = json.readTree(draftResult.getResponse().getContentAsString());
        String requirementId = draft.path("items").get(0).path("requirementId").textValue();
        mvc.perform(get("/api/review-m0/cases/{caseId}/checklist-draft", caseId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].itemVersion").value(0));
        MvcResult editedResult = mvc.perform(patch(
                        "/api/review-m0/cases/{caseId}/checklist-draft/requirements/{requirementId}",
                        caseId, requirementId)
                        .header("Idempotency-Key", "edit-requirement").contentType("application/json")
                        .content("""
                                {"expectedVersion":0,"displayName":"Application form (PDF)","enabled":true,
                                 "applicability":"APPLIES","minimumCount":1,
                                 "fileNameRule":{"kind":"EXACT","pattern":"application.pdf","caseSensitive":true}}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].itemVersion").value(1)).andReturn();
        long revision = json.readTree(editedResult.getResponse().getContentAsString()).path("revision").longValue();
        MvcResult snapshotResult = mvc.perform(post(
                        "/api/review-m0/cases/{caseId}/checklist-snapshots", caseId)
                        .header("Idempotency-Key", "snapshot").contentType("application/json")
                        .content("{\"expectedChecklistRevision\":" + revision
                                + ",\"acknowledgedRequirementIds\":[\"" + requirementId + "\"]}"))
                .andExpect(status().isCreated()).andReturn();
        String snapshotId = json.readTree(snapshotResult.getResponse().getContentAsString())
                .path("checklistSnapshotId").textValue();
        mvc.perform(get("/api/review-m0/cases/{caseId}/checklist-snapshots/{snapshotId}", caseId, snapshotId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].requirementId").value(requirementId));
        mvc.perform(get("/api/review-m0/cases/{caseId}/submission-sources", caseId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/review-m0/cases/{caseId}/materials", caseId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(put("/api/review-m0/cases/{caseId}/assignments", caseId)
                        .header("Idempotency-Key", "empty-assignments").contentType("application/json")
                        .content("{\"expectedAssignmentRevision\":0,\"assignments\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignmentRevision").value(0));
        mvc.perform(get("/api/review-m0/cases/{caseId}/assignments", caseId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignments.length()").value(0));
        assertError(mvc.perform(post("/api/review-m0/cases/{caseId}/reviews", caseId)
                        .header("Idempotency-Key", "review").contentType("application/json")
                        .content("{\"mode\":\"FULL\",\"checklistSnapshotId\":\"" + snapshotId
                                + "\",\"uploadConfirmationId\":\"missing\",\"baseReportId\":null,\"changedMaterialIds\":[]}")),
                409, "REVIEW_M0_UPLOAD_NOT_CONFIRMED");
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_m0_job", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isZero();

        MvcResult uploadResult = mvc.perform(post(
                        "/api/review-m0/cases/{caseId}/upload-confirmations", caseId)
                        .header("Idempotency-Key", "upload").contentType("application/json")
                        .content("{\"checklistSnapshotId\":\"" + snapshotId
                                + "\",\"materialSetRevision\":0,\"acknowledgedUploadComplete\":true}"))
                .andExpect(status().isCreated()).andReturn();
        String uploadId = json.readTree(uploadResult.getResponse().getContentAsString())
                .path("uploadConfirmationId").textValue();
        MvcResult submitted = mvc.perform(post("/api/review-m0/cases/{caseId}/reviews", caseId)
                        .header("Idempotency-Key", "review-ok").contentType("application/json")
                        .content("{\"mode\":\"FULL\",\"checklistSnapshotId\":\"" + snapshotId
                                + "\",\"uploadConfirmationId\":\"" + uploadId
                                + "\",\"baseReportId\":null,\"changedMaterialIds\":[]}"))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = json.readTree(submitted.getResponse().getContentAsString()).path("jobId").textValue();
        context.getBean(ReviewM0JobDispatcher.class).runNext(
                "m006-worker", Instant.now(), Duration.ofMinutes(1), 1);
        mvc.perform(get("/api/review-m0/cases/{caseId}/jobs/{jobId}", caseId, jobId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        MvcResult current = mvc.perform(get("/api/review-m0/cases/{caseId}/reports/current", caseId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.missingCount").value(1)).andReturn();
        JsonNode currentReport = json.readTree(current.getResponse().getContentAsString());
        String reportId = currentReport.path("reportId").textValue();
        String findingId = currentReport.path("findings").get(0).path("findingId").textValue();
        mvc.perform(get("/api/review-m0/cases/{caseId}/reports/{reportId}", caseId, reportId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.findings[0].findingId").value(findingId));
        mvc.perform(post("/api/review-m0/cases/{caseId}/reports/{reportId}/findings/{findingId}/decisions",
                        caseId, reportId, findingId).header("Idempotency-Key", "ignore")
                        .contentType("application/json")
                        .content("{\"decision\":\"IGNORE\",\"reason\":\"accepted risk\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.decision").value("IGNORE"));
        mvc.perform(get("/api/review-m0/cases/{caseId}/reports/current", caseId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.ignoredCount").value(1))
                .andExpect(jsonPath("$.effectiveAssessment").value("M0_NOT_READY"));
    }

    @Test
    void ordinaryUnicodeDownloadPreservesBytesMetadataAndOwnerIsolation() throws Exception {
        setup(true);
        authenticate(OWNER);
        byte[] bytes = SyntheticFileFixtures.pdf("ordinary");
        files.put(OWNER, "file-pdf", "upload.pdf", "application/pdf", bytes, "v1", "secret/object-key");
        InMemoryOwnedFileByteSource.FileFacts before = files.facts("file-pdf");
        String caseId = createCase("download", "download");
        MvcResult attach = mvc.perform(post("/api/review-m0/cases/{caseId}/submission-sources", caseId)
                        .header("Idempotency-Key", "attach-pdf").contentType("application/json")
                        .content("{\"fileId\":\"file-pdf\",\"submissionFileName\":\"initial.pdf\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode material = json.readTree(attach.getResponse().getContentAsString())
                .path("materials").get(0);
        String materialId = material.path("materialId").textValue();
        MvcResult beforeRenameInitial = mvc.perform(get(
                        "/api/review-m0/cases/{caseId}/materials/{materialId}/download", caseId, materialId))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult beforeRename = mvc.perform(asyncDispatch(beforeRenameInitial))
                .andExpect(status().isOk()).andReturn();
        assertIntegrityHeaders(beforeRename, bytes);

        String unicodeName = "奖学金材料（最终）.pdf";
        mvc.perform(patch("/api/review-m0/cases/{caseId}/materials/{materialId}", caseId, materialId)
                        .header("Idempotency-Key", "rename-download-unicode")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":" + material.path("version").longValue()
                                + ",\"submissionFileName\":\"" + unicodeName + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionFileName").value(unicodeName));
        MvcResult initial = mvc.perform(get(
                        "/api/review-m0/cases/{caseId}/materials/{materialId}/download", caseId, materialId))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult download = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertIntegrityHeaders(download, bytes);
        assertThat(download.getResponse().getHeader("X-Content-SHA256"))
                .isEqualTo(beforeRename.getResponse().getHeader("X-Content-SHA256"));
        assertThat(download.getResponse().getHeader("Content-Disposition"))
                .contains("attachment; filename=\"").contains("filename*=UTF-8''")
                .contains("%E5%A5%96%E5%AD%A6%E9%87%91")
                .doesNotContain("\r", "\n");
        assertThat(files.facts("file-pdf")).isEqualTo(before);
        authenticate(OTHER);
        MvcResult hidden = assertError(mvc.perform(get(
                        "/api/review-m0/cases/{caseId}/materials/{materialId}/download", caseId, materialId)),
                404, "REVIEW_M0_CASE_NOT_FOUND");
        assertNoIntegrityHeaders(hidden);
    }

    @Test
    void zipDownloadReturnsOnlyTargetLeafAndUnsupportedTopLevelIs415() throws Exception {
        setup(true);
        authenticate(OWNER);
        byte[] leaf = SyntheticFileFixtures.pdf("zip-leaf");
        byte[] other = SyntheticFileFixtures.pdf("other");
        byte[] zip = SyntheticFileFixtures.zip(List.of(
                SyntheticFileFixtures.entry("folder/first.pdf", other),
                SyntheticFileFixtures.entry("folder/selected.pdf", leaf),
                SyntheticFileFixtures.entry("folder/image.png", SyntheticFileFixtures.png((byte) 9))));
        files.put(OWNER, "file-zip", "bundle.zip", "application/zip", zip, "v1", "private/zip");
        files.put(OWNER, "file-txt", "bad.txt", "text/plain", "bad".getBytes(StandardCharsets.UTF_8), "v1", "private/txt");
        String caseId = createCase("zip", "zip");
        MvcResult attach = mvc.perform(post("/api/review-m0/cases/{caseId}/submission-sources", caseId)
                        .header("Idempotency-Key", "attach-zip").contentType("application/json")
                        .content("{\"fileId\":\"file-zip\",\"submissionFileName\":null}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode materials = json.readTree(attach.getResponse().getContentAsString()).path("materials");
        JsonNode target = null;
        for (JsonNode item : materials) {
            if (item.path("submissionFileName").textValue().equals("selected.pdf")) target = item;
        }
        assertThat(target).isNotNull();
        MvcResult initial = mvc.perform(get(
                        "/api/review-m0/cases/{caseId}/materials/{materialId}/download",
                        caseId, target.path("materialId").textValue()))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult downloaded = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
        assertThat(downloaded.getResponse().getContentAsByteArray())
                .isEqualTo(leaf).isNotEqualTo(other).isNotEqualTo(zip);
        assertThat(downloaded.getResponse().getContentLengthLong()).isEqualTo(leaf.length);
        assertIntegrityHeaders(downloaded, leaf);
        assertThat(downloaded.getResponse().getHeader("X-Content-SHA256"))
                .isNotEqualTo(sha256(other)).isNotEqualTo(sha256(zip));
        assertError(mvc.perform(post("/api/review-m0/cases/{caseId}/submission-sources", caseId)
                        .header("Idempotency-Key", "attach-txt").contentType("application/json")
                        .content("{\"fileId\":\"file-txt\",\"submissionFileName\":\"bad.txt\"}")),
                415, "REVIEW_M0_UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void unavailableDownloadReturnsFrozen503WithoutSuccessIntegrityHeadersOrOriginalBytes() throws Exception {
        setup(true);
        authenticate(OWNER);
        byte[] bytes = SyntheticFileFixtures.pdf("unavailable-download");
        files.put(OWNER, "file-unavailable", "source.pdf", "application/pdf",
                bytes, "v1", "private/unavailable");
        String caseId = createCase("unavailable-download", "unavailable-download");
        MvcResult attach = mvc.perform(post("/api/review-m0/cases/{caseId}/submission-sources", caseId)
                        .header("Idempotency-Key", "attach-unavailable").contentType("application/json")
                        .content("{\"fileId\":\"file-unavailable\",\"submissionFileName\":\"source.pdf\"}"))
                .andExpect(status().isCreated()).andReturn();
        String materialId = json.readTree(attach.getResponse().getContentAsString())
                .path("materials").get(0).path("materialId").textValue();
        files.setAvailable("file-unavailable", false);

        MvcResult unavailable = assertError(mvc.perform(get(
                        "/api/review-m0/cases/{caseId}/materials/{materialId}/download", caseId, materialId)),
                503, "REVIEW_M0_FILE_SERVICE_UNAVAILABLE");
        assertNoIntegrityHeaders(unavailable);
        assertThat(unavailable.getResponse().getContentAsByteArray()).isNotEqualTo(bytes);
    }

    @Test
    void renameCasConflictAndHeaderInjectionAreRejected() throws Exception {
        setup(true);
        authenticate(OWNER);
        files.put(OWNER, "file-rename", "source.pdf", "application/pdf",
                SyntheticFileFixtures.pdf("rename"), "v1", "private/rename");
        String caseId = createCase("rename", "rename");
        MvcResult attach = mvc.perform(post("/api/review-m0/cases/{caseId}/submission-sources", caseId)
                        .header("Idempotency-Key", "attach-rename").contentType("application/json")
                        .content("{\"fileId\":\"file-rename\",\"submissionFileName\":\"one.pdf\"}"))
                .andExpect(status().isCreated()).andReturn();
        String materialId = json.readTree(attach.getResponse().getContentAsString())
                .path("materials").get(0).path("materialId").textValue();
        mvc.perform(patch("/api/review-m0/cases/{caseId}/materials/{materialId}", caseId, materialId)
                        .header("Idempotency-Key", "rename-ok").contentType("application/json")
                        .content("{\"expectedVersion\":0,\"submissionFileName\":\"two.pdf\"}"))
                .andExpect(status().isOk());
        assertError(mvc.perform(patch("/api/review-m0/cases/{caseId}/materials/{materialId}", caseId, materialId)
                        .header("Idempotency-Key", "rename-stale").contentType("application/json")
                        .content("{\"expectedVersion\":0,\"submissionFileName\":\"three.pdf\"}")),
                409, "REVIEW_M0_VERSION_CONFLICT");
        assertError(mvc.perform(patch("/api/review-m0/cases/{caseId}/materials/{materialId}", caseId, materialId)
                        .header("Idempotency-Key", "rename-inject").contentType("application/json")
                        .content("{\"expectedVersion\":1,\"submissionFileName\":\"evil\\r\\nX-Test: yes.pdf\"}")),
                400, "REVIEW_M0_REQUEST_INVALID");
        mvc.perform(delete("/api/review-m0/cases/{caseId}/submission-sources/{sourceId}",
                        caseId, json.readTree(attach.getResponse().getContentAsString()).path("sourceId").textValue())
                        .header("Idempotency-Key", "remove-source").contentType("application/json")
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REMOVED"));
    }

    private void setup(boolean syntheticFiles) {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, fixture::dataSource);
        context.registerBean(JdbcTemplate.class, fixture::jdbc);
        context.registerBean(PlatformTransactionManager.class, fixture::transactions);
        if (syntheticFiles) {
            files = new InMemoryOwnedFileByteSource();
            ReviewM0FileAccess access = new VerifiedReviewM0FileAccess(
                    files, new ZipArchiveEntryOpener(ArchiveSafetyLimits.secureDefaults()));
            context.registerBean(ReviewM0FileAccess.class, () -> access);
            context.registerBean(ReviewM0RemotePreviewAssembler.class, () -> (owner, caseId, revision) -> {
                String basisId = context.getBean(JdbcChecklistStore.class).listBasesOwned(owner, caseId).stream()
                        .filter(item -> item.status() == com.javaee.aiservice.review.m0.model.OfficialBasisStatus.READY)
                        .findFirst().orElseThrow().basisId();
                return List.of(new RemotePreviewSegment(
                        "seg_test", basisId,
                        new OfficialBasisSourceLocator(null, 1, null, null, "block-1", null),
                        true, "explicitly redacted"));
            });
        }
        context.register(
                JdbcReviewCaseStore.class, JdbcReviewReportStore.class,
                JdbcReviewM0IdempotencyStore.class, JdbcReviewM0JobStore.class,
                JdbcReviewM0RemoteCallStore.class, JdbcReviewM0MaterialRepository.class,
                ReviewM0JobSubmissionService.class, ReviewM0JobHandlerRegistry.class,
                ReviewM0JobDispatcher.class, ReviewM0Configuration.class);
        context.refresh();
        ReviewM0Controller controller = new ReviewM0Controller(
                new RequestUserContext(), context.getBean(ReviewM0ApiService.class));
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ReviewM0ApiExceptionHandler())
                .addFilters(new ReviewM0RequestIdFilter()).build();
    }

    private String createCase(String key, String title) throws Exception {
        return createCase(key, title, "LOCAL_ONLY");
    }

    private String createCase(String key, String title, String mode) throws Exception {
        MvcResult result = mvc.perform(post("/api/review-m0/cases")
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"title\":\"" + title + "\",\"processingMode\":\"" + mode + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("caseId").textValue();
    }

    private static List<MockHttpServletRequestBuilder> unauthenticatedRequests() {
        String jsonType = "application/json";
        return List.of(
                post("/api/review-m0/cases").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"title\":\"x\",\"processingMode\":\"LOCAL_ONLY\"}"),
                get("/api/review-m0/cases/c"),
                post("/api/review-m0/cases/c/official-bases").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"fileId\":\"f\",\"role\":\"APPLICATION_NOTICE\"}"),
                delete("/api/review-m0/cases/c/official-bases/b").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"expectedVersion\":0}"),
                get("/api/review-m0/cases/c/official-bases"),
                patch("/api/review-m0/cases/c/official-bases/b").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"expectedVersion\":0,\"role\":\"FILLING_GUIDE\"}"),
                post("/api/review-m0/cases/c/remote-previews").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"operation\":\"CHECKLIST_EXTRACTION\",\"basisRevision\":0}"),
                patch("/api/review-m0/cases/c/remote-previews/p").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"expectedVersion\":0,\"segments\":[]}"),
                post("/api/review-m0/cases/c/remote-previews/p/confirm").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"expectedVersion\":0,\"acknowledgedPayloadDigest\":\"" + "a".repeat(64) + "\"}"),
                post("/api/review-m0/cases/c/checklist-extractions").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"basisRevision\":0,\"processingMode\":\"LOCAL_ONLY\",\"consentId\":null}"),
                get("/api/review-m0/cases/c/checklist-draft"),
                post("/api/review-m0/cases/c/checklist-draft/requirements").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"displayName\":\"x\",\"enabled\":true,\"applicability\":\"APPLIES\",\"minimumCount\":1,\"fileNameRule\":{\"kind\":\"NONE\",\"pattern\":null,\"caseSensitive\":null}}"),
                patch("/api/review-m0/cases/c/checklist-draft/requirements/r").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"expectedVersion\":0,\"displayName\":\"x\",\"enabled\":true,\"applicability\":\"APPLIES\",\"minimumCount\":1,\"fileNameRule\":{\"kind\":\"NONE\",\"pattern\":null,\"caseSensitive\":null}}"),
                post("/api/review-m0/cases/c/checklist-snapshots").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"expectedChecklistRevision\":0,\"acknowledgedRequirementIds\":[]}"),
                get("/api/review-m0/cases/c/checklist-snapshots/s"),
                post("/api/review-m0/cases/c/submission-sources").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"fileId\":\"f\",\"submissionFileName\":\"x.pdf\"}"),
                delete("/api/review-m0/cases/c/submission-sources/s").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"expectedVersion\":0}"),
                get("/api/review-m0/cases/c/submission-sources"),
                get("/api/review-m0/cases/c/materials"),
                patch("/api/review-m0/cases/c/materials/m").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"expectedVersion\":0,\"submissionFileName\":\"x.pdf\"}"),
                put("/api/review-m0/cases/c/assignments").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"expectedAssignmentRevision\":0,\"assignments\":[]}"),
                get("/api/review-m0/cases/c/assignments"),
                post("/api/review-m0/cases/c/upload-confirmations").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"checklistSnapshotId\":\"s\",\"materialSetRevision\":0,\"acknowledgedUploadComplete\":true}"),
                get("/api/review-m0/cases/c/materials/m/download"),
                post("/api/review-m0/cases/c/reviews").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"mode\":\"FULL\",\"checklistSnapshotId\":\"s\",\"uploadConfirmationId\":\"u\",\"baseReportId\":null,\"changedMaterialIds\":[]}"),
                get("/api/review-m0/cases/c/reports/current"),
                get("/api/review-m0/cases/c/reports/r"),
                post("/api/review-m0/cases/c/reports/r/findings/f/decisions").header("Idempotency-Key", "k").contentType(jsonType)
                        .content("{\"decision\":\"IGNORE\",\"reason\":null}"),
                get("/api/review-m0/cases/c/jobs/j"),
                post("/api/review-m0/cases/c/jobs/j/cancel").header("Idempotency-Key", "k"));
    }

    private MvcResult assertError(
            org.springframework.test.web.servlet.ResultActions action,
            int status, String code
    ) throws Exception {
        MvcResult result = action.andExpect(status().is(status)).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("errorCode", "message", "requestId", "details");
        assertThat(body.path("errorCode").textValue()).isEqualTo(code);
        assertThat(body.path("requestId").textValue()).startsWith("req_");
        assertThat(body.path("details").isObject()).isTrue();
        return result;
    }

    private static void assertIntegrityHeaders(MvcResult result, byte[] expectedBytes) throws Exception {
        byte[] actualBytes = result.getResponse().getContentAsByteArray();
        assertThat(actualBytes).isEqualTo(expectedBytes);
        String sha256 = sha256(actualBytes);
        assertThat(result.getResponse().getHeader("ETag")).isEqualTo('"' + sha256 + '"');
        assertThat(result.getResponse().getHeader("X-Content-SHA256")).isEqualTo(sha256);
        assertThat(result.getResponse().getHeader("Digest"))
                .isEqualTo("sha-256=" + Base64.getEncoder().encodeToString(
                        HexFormat.of().parseHex(sha256)));
        assertThat(result.getResponse().getHeader("dIgEsT"))
                .isEqualTo(result.getResponse().getHeader("Digest"));
        assertThat(result.getResponse().getContentLengthLong()).isEqualTo(actualBytes.length);
    }

    private static void assertNoIntegrityHeaders(MvcResult result) {
        assertThat(result.getResponse().getHeader("ETag")).isNull();
        assertThat(result.getResponse().getHeader("Digest")).isNull();
        assertThat(result.getResponse().getHeader("X-Content-SHA256")).isNull();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void authenticate(String owner) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(owner, "n/a", "ROLE_USER"));
    }
}
