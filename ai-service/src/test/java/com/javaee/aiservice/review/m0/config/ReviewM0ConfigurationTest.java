package com.javaee.aiservice.review.m0.config;

import com.javaee.aiservice.review.m0.api.ReviewM0ApiService;
import com.javaee.aiservice.review.m0.extraction.RemoteCandidateClientFactory;
import com.javaee.aiservice.review.m0.file.OwnedFileAccessException;
import com.javaee.aiservice.review.m0.file.HttpOwnedFileByteSource;
import com.javaee.aiservice.review.m0.file.OwnedFileByteSource;
import com.javaee.aiservice.review.m0.file.ReviewM0FileAccess;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.job.*;
import com.javaee.aiservice.review.m0.material.JdbcReviewM0MaterialRepository;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.store.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewM0ConfigurationTest {

    @Test
    void productionCompositionHasAllHandlersAndNoRemoteClient() {
        try (AnnotationConfigApplicationContext context = context()) {
            ReviewM0JobHandlerRegistry registry = context.getBean(ReviewM0JobHandlerRegistry.class);
            assertThat(ReviewJobType.values()).allSatisfy(type -> assertThat(registry.find(type)).isPresent());
            assertThat(context.getBeansOfType(ReviewM0JobHandler.class)).hasSize(3);
            assertThat(context.getBeansOfType(RemoteCandidateClientFactory.class)).isEmpty();
            assertThat(context.getBean(ReviewM0ApiService.class)).isNotNull();
        }
    }

    @Test
    void productionOwnerAwareFileGatewayUsesRealClientAndFailsClosedWithoutDelegatedIdentity() {
        try (AnnotationConfigApplicationContext context = context()) {
            assertThat(context.getBean(OwnedFileByteSource.class))
                    .isInstanceOf(HttpOwnedFileByteSource.class);
            ReviewM0FileAccess files = context.getBean(ReviewM0FileAccess.class);
            assertThatThrownBy(() -> files.getOwnedFile("owner-a", "file-a"))
                    .isInstanceOf(OwnedFileAccessException.class)
                    .extracting(error -> ((OwnedFileAccessException) error).kind())
                    .isEqualTo(OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void missingOrDuplicateHandlersFailFast() {
        ReviewM0JobHandler onlyFormal = new ReviewM0JobHandler() {
            @Override public ReviewJobType jobType() { return ReviewJobType.FORMAL_REVIEW; }
            @Override public void handle(com.javaee.aiservice.review.m0.model.ReviewJob job,
                                         ReviewM0JobStore store) { }
        };
        ReviewM0JobHandlerRegistry incomplete = new ReviewM0JobHandlerRegistry(List.of(onlyFormal));
        assertThatThrownBy(() -> new ReviewM0Configuration()
                .reviewM0RequiredHandlerValidator(incomplete, List.of(onlyFormal))
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ReviewM0JobHandlerRegistry(List.of(onlyFormal, onlyFormal)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void illegalTypedLimitsFailFast() {
        ReviewM0RuntimeProperties properties = new ReviewM0RuntimeProperties();
        properties.setMaximumArchiveEntries(0);
        assertThatThrownBy(properties::archiveSafetyLimits).isInstanceOf(IllegalArgumentException.class);
        properties = new ReviewM0RuntimeProperties();
        properties.setRegexTimeout(java.time.Duration.ofSeconds(6));
        assertThatThrownBy(properties::validatedRegexTimeout).isInstanceOf(IllegalStateException.class);
        properties = new ReviewM0RuntimeProperties();
        properties.setFileServiceBaseUri(java.net.URI.create("file:/not-http"));
        assertThatThrownBy(properties::validatedFileServiceBaseUri).isInstanceOf(IllegalStateException.class);
        properties = new ReviewM0RuntimeProperties();
        properties.setFileServiceRequestTimeout(java.time.Duration.ofSeconds(31));
        assertThatThrownBy(properties::validatedFileServiceRequestTimeout)
                .isInstanceOf(IllegalStateException.class);
    }

    static AnnotationConfigApplicationContext context() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, fixture::dataSource);
        context.registerBean(JdbcTemplate.class, fixture::jdbc);
        context.registerBean(PlatformTransactionManager.class, fixture::transactions);
        context.register(
                JdbcReviewCaseStore.class,
                JdbcReviewReportStore.class,
                JdbcReviewM0IdempotencyStore.class,
                JdbcReviewM0JobStore.class,
                JdbcReviewM0RemoteCallStore.class,
                JdbcReviewM0MaterialRepository.class,
                ReviewM0JobSubmissionService.class,
                ReviewM0JobHandlerRegistry.class,
                ReviewM0JobDispatcher.class,
                ReviewM0Configuration.class);
        context.refresh();
        return context;
    }
}
