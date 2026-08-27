package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewM0KeyDigests;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.SubmissionSource;
import com.javaee.aiservice.review.m0.model.SubmissionSourceStatus;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReviewM0MaterialDigestKeyTest {
    @Test
    void fullUtf8mb4PathsRemainDistinctAndTheExactFullPathRemainsUnique() {
        Fixture fixture = fixture();
        String firstPath = "甲/" + "界".repeat(2038) + ".pdf";
        String secondPath = "乙/" + "界".repeat(2038) + ".pdf";

        fixture.repository().insertMaterialOwned("owner", material("material-a", firstPath));
        fixture.repository().insertMaterialOwned("owner", material("material-b", secondPath));

        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_material WHERE source_id='source-zip'", Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> fixture.repository().insertMaterialOwned(
                "owner", material("material-duplicate", firstPath)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT);
    }

    @Test
    void simulatedDigestCollisionFailsClosedAfterComparingTheFullStoredPath() {
        Fixture fixture = fixture();
        String storedPath = "stored/proof.pdf";
        String collidingPath = "different/proof.pdf";
        fixture.repository().insertMaterialOwned("owner", material("material-a", storedPath));
        fixture.jdbc().update(
                "UPDATE review_m0_material SET zip_entry_scope_digest=? WHERE material_id='material-a'",
                ReviewM0KeyDigests.zipEntryScope("source-zip", collidingPath));

        assertThatThrownBy(() -> fixture.repository().insertMaterialOwned(
                "owner", material("material-b", collidingPath)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_material", Integer.class)).isEqualTo(1);
    }

    private static Fixture fixture() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-a", "owner");
        JdbcReviewM0MaterialRepository repository = new JdbcReviewM0MaterialRepository(fixture.jdbc());
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        repository.insertSourceOwned("owner", new SubmissionSource(
                "source-zip", "case-a", "file-zip", "container.zip", "application/zip",
                FormalFileType.ZIP, SubmissionSourceStatus.ATTACHED, 1, "a".repeat(64),
                "v1", 1, null, 0, now, now));
        return new Fixture(fixture, repository);
    }

    private static SubmissionMaterial material(String materialId, String path) {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        return new SubmissionMaterial(
                materialId, "case-a", "source-zip", MaterialKind.ZIP_ENTRY, path,
                "proof.pdf", 0, "b".repeat(64), FormalFileType.PDF,
                "proof.pdf", "proof.pdf", MaterialStatus.AVAILABLE, null, null,
                1, 0, 0, now, now);
    }

    private record Fixture(
            ReviewM0JdbcTestFixture delegate,
            JdbcReviewM0MaterialRepository repository
    ) {
        private org.springframework.jdbc.core.JdbcTemplate jdbc() {
            return delegate.jdbc();
        }
    }
}
