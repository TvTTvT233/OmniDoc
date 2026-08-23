package com.javaee.aiservice.review.m0.config;

import com.javaee.aiservice.review.m0.api.*;
import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.archive.ZipArchiveEntryOpener;
import com.javaee.aiservice.review.m0.archive.ZipArchiveInspector;
import com.javaee.aiservice.review.m0.checklist.*;
import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.extraction.*;
import com.javaee.aiservice.review.m0.file.FormalContentInspector;
import com.javaee.aiservice.review.m0.file.HttpOwnedFileByteSource;
import com.javaee.aiservice.review.m0.file.OwnedFileByteSource;
import com.javaee.aiservice.review.m0.file.ReviewM0FileAccess;
import com.javaee.aiservice.review.m0.file.VerifiedReviewM0FileAccess;
import com.javaee.aiservice.review.m0.idempotency.ReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.job.*;
import com.javaee.aiservice.review.m0.material.*;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.privacy.*;
import com.javaee.aiservice.review.m0.report.*;
import com.javaee.aiservice.review.m0.review.*;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import com.javaee.aiservice.review.m0.store.ReviewReportStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReviewM0RuntimeProperties.class)
public class ReviewM0Configuration {

    @Bean("reviewM0Clock")
    @ConditionalOnMissingBean(name = "reviewM0Clock")
    Clock reviewM0Clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(M0IdGenerator.class)
    M0IdGenerator reviewM0IdGenerator() {
        return M0IdGenerator.randomUuid();
    }

    @Bean
    ArchiveSafetyLimits reviewM0ArchiveSafetyLimits(ReviewM0RuntimeProperties properties) {
        return properties.archiveSafetyLimits();
    }

    @Bean
    FormalContentInspector reviewM0FormalContentInspector(ArchiveSafetyLimits limits) {
        return new FormalContentInspector(limits);
    }

    @Bean
    ZipArchiveInspector reviewM0ZipArchiveInspector(
            ArchiveSafetyLimits limits, FormalContentInspector contentInspector
    ) {
        return new ZipArchiveInspector(limits, contentInspector);
    }

    @Bean
    ZipArchiveEntryOpener reviewM0ZipArchiveEntryOpener(ArchiveSafetyLimits limits) {
        return new ZipArchiveEntryOpener(limits);
    }

    @Bean
    @ConditionalOnMissingBean(OwnedFileByteSource.class)
    OwnedFileByteSource reviewM0OwnedFileByteSource(ReviewM0RuntimeProperties properties) {
        return new HttpOwnedFileByteSource(
                properties.validatedFileServiceBaseUri(),
                properties.validatedFileServiceConnectTimeout(),
                properties.validatedFileServiceRequestTimeout());
    }

    @Bean
    @ConditionalOnMissingBean(ReviewM0FileAccess.class)
    ReviewM0FileAccess reviewM0FileAccess(
            OwnedFileByteSource byteSource,
            ZipArchiveEntryOpener opener
    ) {
        return new VerifiedReviewM0FileAccess(byteSource, opener);
    }

    @Bean
    @ConditionalOnMissingBean(OfficialBasisContentProvider.class)
    OfficialBasisContentProvider reviewM0FailClosedBasisContentProvider() {
        return (owner, caseId, revision) -> {
            throw new ReviewM0JobExecutionException(
                    ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE, true,
                    "Owner-aware official basis content adapter is unavailable", null);
        };
    }

    @Bean
    @ConditionalOnMissingBean(LocalChecklistCandidateExtractor.class)
    LocalChecklistCandidateExtractor reviewM0FailClosedLocalExtractor() {
        return segments -> {
            throw new ReviewM0JobExecutionException(
                    ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR, false,
                    "Local checklist extraction adapter is unavailable", null);
        };
    }

    @Bean
    @ConditionalOnMissingBean(ReviewM0RemotePreviewAssembler.class)
    ReviewM0RemotePreviewAssembler reviewM0FailClosedPreviewAssembler() {
        return (owner, caseId, revision) -> {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE,
                    "Owner-aware preview source and redactor are unavailable");
        };
    }

    @Bean
    JdbcChecklistStore reviewM0ChecklistStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, M0IdGenerator ids
    ) {
        return new JdbcChecklistStore(jdbc, transactions, ids);
    }

    @Bean
    OfficialBasisService reviewM0OfficialBasisService(
            ReviewCaseStore cases, JdbcChecklistStore store, PlatformTransactionManager transactions,
            @Qualifier("reviewM0Clock") Clock clock
    ) {
        return new OfficialBasisService(cases, store, transactions, clock);
    }

    @Bean
    ChecklistService reviewM0ChecklistService(
            ReviewCaseStore cases, JdbcChecklistStore store, PlatformTransactionManager transactions,
            M0IdGenerator ids, @Qualifier("reviewM0Clock") Clock clock
    ) {
        return new ChecklistService(cases, store, transactions, ids, clock);
    }

    @Bean
    JdbcRemotePrivacyStore reviewM0RemotePrivacyStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, M0IdGenerator ids
    ) {
        return new JdbcRemotePrivacyStore(jdbc, transactions, ids);
    }

    @Bean
    RemotePreviewService reviewM0RemotePreviewService(
            ReviewCaseStore cases, JdbcChecklistStore checklistStore,
            JdbcRemotePrivacyStore privacyStore, @Qualifier("reviewM0Clock") Clock clock
    ) {
        return new RemotePreviewService(cases, checklistStore, privacyStore, clock);
    }

    @Bean
    RemoteConsentVerifier reviewM0RemoteConsentVerifier(
            ReviewCaseStore cases, ReviewM0JobStore jobs, JdbcRemotePrivacyStore privacyStore,
            ReviewM0RemoteCallStore remoteCalls, M0IdGenerator ids
    ) {
        return new RemoteConsentVerifier(cases, jobs, privacyStore, remoteCalls, ids);
    }

    @Bean
    MaterialLifecycleService reviewM0MaterialLifecycleService(
            JdbcReviewM0MaterialRepository repository, ReviewCaseStore cases,
            ReviewM0FileAccess files, ZipArchiveInspector archives,
            FormalContentInspector contentInspector, PlatformTransactionManager transactions
    ) {
        return new MaterialLifecycleService(
                repository, cases, files, archives, contentInspector, transactions);
    }

    @Bean
    MaterialDownloadService reviewM0MaterialDownloadService(
            JdbcReviewM0MaterialRepository repository, ReviewM0FileAccess files
    ) {
        return new MaterialDownloadService(repository, files);
    }

    @Bean
    ReviewReportReader reviewM0ReportReader(
            JdbcTemplate jdbc, ReviewReportStore reports, ReviewM0JobStore jobs
    ) {
        return new JdbcReviewReportReader(jdbc, reports, jobs);
    }

    @Bean
    IncrementalReviewValidator reviewM0IncrementalReviewValidator() {
        return new IncrementalReviewValidator();
    }

    @Bean
    ReviewInputProvider reviewM0InputProvider(
            ReviewCaseStore cases, ChecklistService checklistService,
            JdbcChecklistStore checklistStore, MaterialLifecycleService materialService
    ) {
        return new CurrentReviewInputProvider(
                cases, checklistService, checklistStore, materialService,
                DeterministicReviewEngine.ENGINE_VERSION);
    }

    @Bean
    ReviewEvidenceFactory reviewM0EvidenceFactory() {
        return new ReviewEvidenceFactory();
    }

    @Bean
    SafeRegexMatcher reviewM0RegexMatcher(ReviewM0RuntimeProperties properties) {
        return new BoundedRegexMatcher(properties.validatedRegexTimeout());
    }

    @Bean
    DeterministicReviewEngine reviewM0ReviewEngine(
            SafeRegexMatcher regex, ReviewEvidenceFactory evidence
    ) {
        return new DeterministicReviewEngine(regex, evidence);
    }

    @Bean
    ReviewReportAssembler reviewM0ReportAssembler(@Qualifier("reviewM0Clock") Clock clock) {
        return new ReviewReportAssembler(clock);
    }

    @Bean
    ReviewM0ReviewJobExecutor reviewM0ReviewJobExecutor(
            ReviewInputProvider inputs, ReviewReportReader reader,
            IncrementalReviewValidator incremental, DeterministicReviewEngine engine,
            ReviewReportAssembler assembler, ReviewReportStore reports,
            @Qualifier("reviewM0Clock") Clock clock
    ) {
        return new ReviewM0ReviewJobExecutor(
                inputs, reader, incremental, engine, assembler, reports, clock);
    }

    @Bean
    FormalReviewSubmissionService reviewM0FormalReviewSubmissionService(
            JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ReviewInputProvider inputs, ReviewReportReader reader,
            IncrementalReviewValidator incremental,
            ReviewM0JobSubmissionService submissions,
            @Qualifier("reviewM0Clock") Clock clock
    ) {
        return new FormalReviewSubmissionService(
                jdbc, transactions, inputs, reader, incremental, submissions, clock);
    }

    @Bean
    FindingDecisionService reviewM0FindingDecisionService(
            JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ReviewReportReader reports, @Qualifier("reviewM0Clock") Clock clock
    ) {
        return new FindingDecisionService(jdbc, transactions, reports, clock);
    }

    @Bean
    ChecklistExtractionJobHandler reviewM0ChecklistExtractionJobHandler(
            ReviewCaseStore cases, ChecklistService checklists,
            OfficialBasisContentProvider content, LocalChecklistCandidateExtractor extractor,
            RemoteConsentVerifier consent, ReviewM0RemoteCallStore remoteCalls,
            ObjectProvider<RemoteCandidateClientFactory> remoteClients,
            @Qualifier("reviewM0Clock") Clock clock
    ) {
        return new ChecklistExtractionJobHandler(
                cases, checklists, content, extractor, consent, remoteCalls,
                remoteClients.getIfAvailable(), clock);
    }

    @Bean
    FormalReviewJobHandler reviewM0FormalReviewJobHandler(ReviewM0ReviewJobExecutor executor) {
        return new FormalReviewJobHandler(executor);
    }

    @Bean
    IncrementalReviewJobHandler reviewM0IncrementalReviewJobHandler(ReviewM0ReviewJobExecutor executor) {
        return new IncrementalReviewJobHandler(executor);
    }

    @Bean
    SmartInitializingSingleton reviewM0RequiredHandlerValidator(
            ReviewM0JobHandlerRegistry registry, List<ReviewM0JobHandler> handlers
    ) {
        return () -> {
            Set<ReviewJobType> required = EnumSet.of(
                    ReviewJobType.CHECKLIST_EXTRACTION,
                    ReviewJobType.FORMAL_REVIEW,
                    ReviewJobType.INCREMENTAL_REVIEW);
            Set<ReviewJobType> actual = EnumSet.noneOf(ReviewJobType.class);
            for (ReviewM0JobHandler handler : handlers) actual.add(handler.jobType());
            if (!actual.equals(required)
                    || required.stream().anyMatch(type -> registry.find(type).isEmpty())) {
                throw new IllegalStateException(
                        "Review M0 requires exactly one handler for each frozen Job type");
            }
        };
    }

    @Bean
    ReviewM0ApiIdempotency reviewM0ApiIdempotency(
            ReviewM0IdempotencyStore store, PlatformTransactionManager transactions
    ) {
        return new ReviewM0ApiIdempotency(store, transactions);
    }

    @Bean
    ReviewM0ApiMapper reviewM0ApiMapper(JdbcTemplate jdbc) {
        return new ReviewM0ApiMapper(jdbc);
    }

    @Bean
    ReviewM0ApiService reviewM0ApiService(
            ReviewCaseStore cases, OfficialBasisService basisService,
            JdbcChecklistStore checklistStore, ChecklistService checklistService,
            RemotePreviewService previewService, JdbcRemotePrivacyStore privacyStore,
            ReviewM0RemotePreviewAssembler previewAssembler, ReviewM0FileAccess files,
            MaterialLifecycleService materialService,
            JdbcReviewM0MaterialRepository materialRepository,
            MaterialDownloadService downloadService,
            FormalReviewSubmissionService formalSubmission,
            ReviewM0JobSubmissionService jobSubmission, ReviewM0JobStore jobs,
            FindingDecisionService decisions, ReviewM0ApiIdempotency idempotency,
            ReviewM0ApiMapper mapper, M0IdGenerator ids, JdbcTemplate jdbc,
            @Qualifier("reviewM0Clock") Clock clock
    ) {
        return new ReviewM0ApiService(
                cases, basisService, checklistStore, checklistService,
                previewService, privacyStore, previewAssembler, files,
                materialService, materialRepository, downloadService,
                formalSubmission, jobSubmission, jobs, decisions,
                idempotency, mapper, ids, jdbc, clock);
    }

    @Bean
    FilterRegistrationBean<ReviewM0RequestIdFilter> reviewM0RequestIdFilter() {
        FilterRegistrationBean<ReviewM0RequestIdFilter> registration =
                new FilterRegistrationBean<>(new ReviewM0RequestIdFilter());
        registration.addUrlPatterns("/api/review-m0/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
