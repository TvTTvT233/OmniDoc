package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.api.ReviewM0ApiIdempotency;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyResponseSnapshot;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.material.JdbcReviewM0MaterialRepository;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewM0KeyDigests;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit-only gate: this class is deliberately named IT and never skips a missing real MySQL dependency. */
class ReviewM0RealMysql8AcceptanceIT {
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connectToRealMysql8() throws Exception {
        String url = required("REVIEW_M0_MYSQL_JDBC_URL");
        String user = required("REVIEW_M0_MYSQL_USER");
        String password = required("REVIEW_M0_MYSQL_PASSWORD");
        MysqlDataSource mysql = new MysqlDataSource();
        mysql.setUrl(url);
        mysql.setUser(user);
        mysql.setPassword(password);
        dataSource = mysql;
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.0.");
        assertThat(jdbc.queryForObject("SELECT @@innodb_page_size", Integer.class)).isEqualTo(16384);
        assertThat(jdbc.queryForObject("SELECT @@character_set_database", String.class)).isEqualTo("utf8mb4");
        resetSchema();
    }

    @AfterAll
    static void leaveTheDedicatedDatabaseEmpty() {
        if (jdbc != null) {
            resetSchema();
        }
    }

    @Test
    void coldRepeatedPartialAndUpgradePathsAllPassOnMysql8() throws Exception {
        ReviewM0SchemaInitializer cold = initializer();
        cold.afterPropertiesSet();
        cold.afterPropertiesSet();
        assertCompleteSchemaAndIndexBudget();

        resetSchema();
        String[] statements = v1Statements();
        assertThat(statements).hasSize(22);
        for (int index = 0; index < 8; index++) {
            jdbc.execute(statements[index]);
        }
        assertThat(reviewTableCount()).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_schema_version", Integer.class)).isZero();
        initializer().afterPropertiesSet();
        assertCompleteSchemaAndIndexBudget();

        resetSchema();
        run(ReviewM0SchemaInitializer.V1_RESOURCE);
        jdbc.update("INSERT INTO review_m0_schema_version VALUES (1, 'v1', ?)", now());
        run(ReviewM0SchemaInitializer.V2_RESOURCE);
        jdbc.update("INSERT INTO review_m0_schema_version VALUES (2, 'v2', ?)", now());
        IdempotencyScope oldScope = new IdempotencyScope(
                "拥".repeat(120), "POST", "/" + "路".repeat(1000), "键".repeat(250));
        String scopeDigest = ReviewM0KeyDigests.idempotencyScope(
                oldScope.ownerId(), oldScope.httpMethod(), oldScope.canonicalPath(), oldScope.idempotencyKey());
        jdbc.update("""
                        INSERT INTO review_m0_idempotency(
                            scope_digest, owner_id, http_method, canonical_path, idempotency_key,
                            request_digest, response_status, response_type, response_id, state,
                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 201, 'CASE', 'case-old', 'COMPLETED', ?, ?)
                        """, scopeDigest, oldScope.ownerId(), oldScope.httpMethod(), oldScope.canonicalPath(),
                oldScope.idempotencyKey(), "a".repeat(64), now(), now());

        initializer().afterPropertiesSet();
        assertCompleteSchemaAndIndexBudget();
        assertThat(jdbc.queryForObject(
                "SELECT request_digest FROM review_m0_idempotency WHERE scope_digest=?",
                String.class, scopeDigest)).isEqualTo("a".repeat(64));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot", Integer.class)).isZero();

        JdbcReviewM0IdempotencyStore upgradedStore = store();
        IdempotencyResponseSnapshot replacement = IdempotencyResponseSnapshot.capture(
                201, "CASE", "case-old", "{\"caseId\":\"case-old\"}");
        assertThatThrownBy(() -> upgradedStore.completeWithSnapshot(
                oldScope, "a".repeat(64), 201, "CASE", "case-old", replacement))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE);
    }

    @Test
    void longScopesConcurrentUniquenessCollisionChecksAndV3ChecksAreReal() throws Exception {
        if (reviewTableCount() == 0) {
            initializer().afterPropertiesSet();
        }
        IdempotencyScope scope = new IdempotencyScope(
                "户".repeat(128), "POST", "/" + "径".repeat(1023), "幂".repeat(255));
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        JdbcReviewM0IdempotencyStore store = new JdbcReviewM0IdempotencyStore(jdbc, transactionManager);
        // Production concurrency is owned by the full API transaction, not by direct store retries.
        ReviewM0ApiIdempotency idempotency = new ReviewM0ApiIdempotency(store, transactionManager);
        AtomicInteger businessAttempts = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<ReviewM0ApiIdempotency.Result<String>>> futures =
                    new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return idempotency.execute(
                            scope, "b".repeat(64), 201, "REAL_MYSQL_LONG_SCOPE", String.class,
                            () -> {
                                businessAttempts.incrementAndGet();
                                jdbc.update("""
                                        INSERT INTO review_m0_case(
                                            case_id, user_id, title, processing_mode, basis_revision,
                                            checklist_revision, material_set_revision, assignment_revision,
                                            name_revision, version, current_checklist_snapshot_id, current_report_id,
                                            created_at, updated_at)
                                        VALUES ('case-long-scope', ?, 'stable-result', 'LOCAL_ONLY',
                                                0, 0, 0, 0, 0, 0, NULL, NULL, ?, ?)
                                        """, scope.ownerId(), now(), now());
                                return new ReviewM0ApiIdempotency.Resource<>(
                                        "case-long-scope", "stable-result");
                            });
                }));
            }
            start.countDown();
            List<ReviewM0ApiIdempotency.Result<String>> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get());
            }
            assertThat(results).hasSize(8).allSatisfy(result -> {
                assertThat(result.status()).isEqualTo(201);
                assertThat(result.value()).isEqualTo("stable-result");
            });
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).filteredOn(ReviewM0ApiIdempotency.Result::replayed).hasSize(7);
        } finally {
            executor.shutdownNow();
        }
        String scopeDigest = ReviewM0KeyDigests.idempotencyScope(
                scope.ownerId(), scope.httpMethod(), scope.canonicalPath(), scope.idempotencyKey());
        assertThat(businessAttempts).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_case WHERE case_id='case-long-scope'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency WHERE scope_digest=? AND state='COMPLETED'",
                Integer.class, scopeDigest)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot WHERE scope_digest=?",
                Integer.class, scopeDigest)).isEqualTo(1);

        jdbc.update("UPDATE review_m0_idempotency SET canonical_path='/collision' WHERE scope_digest=?",
                scopeDigest);
        assertThatThrownBy(() -> store.find(scope))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE);

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO review_m0_idempotency_response_snapshot(
                            scope_digest, owner_id, http_method, canonical_path, idempotency_key,
                            format_version, response_body, response_body_bytes, snapshot_digest, created_at)
                        VALUES (?, 'owner', 'POST', '/x', 'key', 2, '{}', 2, ?, ?)
                        """, "c".repeat(64), "d".repeat(64), now()))
                .isInstanceOf(DataAccessException.class);

        insertMaterialParents();
        JdbcReviewM0MaterialRepository materials = new JdbcReviewM0MaterialRepository(jdbc);
        String longPathA = "甲/" + "界".repeat(2038) + ".pdf";
        String longPathB = "乙/" + "界".repeat(2038) + ".pdf";
        materials.insertMaterialOwned("owner-material", material("material-long-a", longPathA));
        materials.insertMaterialOwned("owner-material", material("material-long-b", longPathB));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_material WHERE source_id='source-material'", Integer.class))
                .isEqualTo(2);

        String concurrentPath = "concurrent/" + "并".repeat(1900) + ".pdf";
        CountDownLatch materialStart = new CountDownLatch(1);
        var materialExecutor = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<ReviewM0ErrorCode>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                int ordinal = index;
                futures.add(materialExecutor.submit(() -> {
                    materialStart.await();
                    try {
                        materials.insertMaterialOwned(
                                "owner-material", material("material-race-" + ordinal, concurrentPath));
                        return null;
                    } catch (ReviewM0Exception error) {
                        return error.errorCode();
                    }
                }));
            }
            materialStart.countDown();
            List<ReviewM0ErrorCode> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get());
            }
            assertThat(results).filteredOn(value -> value == null).hasSize(1);
            assertThat(results).filteredOn(value -> value == ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT)
                    .hasSize(7);
        } finally {
            materialExecutor.shutdownNow();
        }

        String collisionPath = "collision/proof.pdf";
        jdbc.update("""
                        UPDATE review_m0_material SET zip_entry_scope_digest=?
                        WHERE material_id='material-long-a'
                        """, ReviewM0KeyDigests.zipEntryScope("source-material", collisionPath));
        assertThatThrownBy(() -> materials.insertMaterialOwned(
                "owner-material", material("material-collision", collisionPath)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE);
    }

    private static void assertCompleteSchemaAndIndexBudget() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_schema_version", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT GROUP_CONCAT(version ORDER BY version) FROM review_m0_schema_version",
                String.class)).isEqualTo("1,2,3");
        assertThat(reviewTableCount()).isEqualTo(23);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name LIKE 'review_m0_%' "
                        + "AND table_name<>'review_m0_idempotency_response_snapshot'",
                Integer.class)).isEqualTo(22);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints
                WHERE constraint_schema=DATABASE() AND constraint_name IN (
                    'fk_review_m0_case_current_checklist','fk_review_m0_case_current_report',
                    'fk_review_m0_checklist_revision_job','fk_review_m0_remote_consent_job')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema=DATABASE()
                  AND constraint_name IN ('ck_review_m0_idempotency_snapshot_format',
                                          'ck_review_m0_idempotency_snapshot_size')
                  AND constraint_type='CHECK' AND enforced='YES'
                """, Integer.class)).isEqualTo(2);

        Map<String, Long> bytesByIndex = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.table_name, s.index_name, s.seq_in_index, s.column_name, s.sub_part,
                       c.data_type, c.character_maximum_length, c.character_set_name
                FROM information_schema.statistics s
                JOIN information_schema.columns c
                  ON c.table_schema=s.table_schema AND c.table_name=s.table_name
                 AND c.column_name=s.column_name
                WHERE s.table_schema=DATABASE() AND s.table_name LIKE 'review_m0_%'
                ORDER BY s.table_name, s.index_name, s.seq_in_index
                """, row -> {
            assertThat(row.getObject("sub_part"))
                    .as("unsafe prefix index %s.%s", row.getString("table_name"), row.getString("index_name"))
                    .isNull();
            String charset = row.getString("character_set_name");
            if (charset != null) {
                assertThat(charset).isEqualTo("utf8mb4");
            }
            String key = row.getString("table_name") + "." + row.getString("index_name");
            bytesByIndex.merge(key, worstBytes(
                    row.getString("data_type"), row.getLong("character_maximum_length")), Long::sum);
        });
        assertThat(bytesByIndex).isNotEmpty();
        assertThat(bytesByIndex).allSatisfy((name, bytes) ->
                assertThat(bytes).as(name).isLessThanOrEqualTo(3072));
        assertThat(bytesByIndex.get("review_m0_material.uk_review_m0_material_zip_entry"))
                .isEqualTo(256);
        assertThat(bytesByIndex.get("review_m0_idempotency.PRIMARY")).isEqualTo(256);
    }

    private static long worstBytes(String dataType, long characterLength) {
        return switch (dataType.toLowerCase()) {
            case "char", "varchar" -> characterLength * 4;
            case "binary", "varbinary" -> characterLength;
            case "bigint", "datetime", "timestamp" -> 8;
            case "int", "integer" -> 4;
            case "smallint" -> 2;
            case "tinyint", "boolean", "bit" -> 1;
            default -> throw new AssertionError("Unaccounted indexed MySQL type: " + dataType);
        };
    }

    private static ReviewM0SchemaInitializer initializer() {
        ReviewM0PersistenceProperties properties = new ReviewM0PersistenceProperties();
        properties.setEnabled(true);
        properties.getSchema().getAutoMigration().setEnabled(true);
        return new ReviewM0SchemaInitializer(dataSource, properties);
    }

    private static void insertMaterialParents() {
        jdbc.update("""
                        INSERT INTO review_m0_case(
                            case_id, user_id, title, processing_mode, basis_revision,
                            checklist_revision, material_set_revision, assignment_revision,
                            name_revision, version, current_checklist_snapshot_id, current_report_id,
                            created_at, updated_at)
                        VALUES ('case-material', 'owner-material', 'synthetic', 'LOCAL_ONLY',
                                0, 0, 0, 0, 0, 0, NULL, NULL, ?, ?)
                        """, now(), now());
        jdbc.update("""
                        INSERT INTO review_m0_submission_source(
                            source_id, case_id, file_id, original_file_name_snapshot,
                            declared_content_type, formal_file_type, status, file_size_bytes,
                            file_digest, storage_version, attached_material_set_revision,
                            removed_material_set_revision, version, created_at, updated_at)
                        VALUES ('source-material', 'case-material', 'file-material', 'synthetic.zip',
                                'application/zip', 'ZIP', 'ATTACHED', 1, ?, 'v1', 1, NULL, 0, ?, ?)
                        """, "e".repeat(64), now(), now());
    }

    private static SubmissionMaterial material(String materialId, String path) {
        Instant instant = Instant.now();
        return new SubmissionMaterial(
                materialId, "case-material", "source-material", MaterialKind.ZIP_ENTRY,
                path, "proof.pdf", 0, "f".repeat(64), FormalFileType.PDF,
                "proof.pdf", "proof.pdf", MaterialStatus.AVAILABLE, null, null,
                1, 0, 0, instant, instant);
    }

    private static JdbcReviewM0IdempotencyStore store() {
        return new JdbcReviewM0IdempotencyStore(
                jdbc, new DataSourceTransactionManager(dataSource));
    }

    private static String[] v1Statements() throws Exception {
        String script = Files.readString(
                new ClassPathResource(ReviewM0SchemaInitializer.V1_RESOURCE).getFile().toPath(),
                StandardCharsets.UTF_8);
        return Arrays.stream(script.split(";"))
                .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
    }

    private static void run(String resource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(resource));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }

    private static int reviewTableCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name LIKE 'review_m0_%'", Integer.class);
    }

    private static void resetSchema() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                List<String> tables = new ArrayList<>();
                try (var rows = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema=DATABASE() AND table_name LIKE 'review_m0_%'")) {
                    while (rows.next()) {
                        tables.add(rows.getString(1));
                    }
                }
                for (String table : tables) {
                    if (!table.matches("review_m0_[a-z0-9_]+")) {
                        throw new AssertionError("Unexpected table name in dedicated acceptance database");
                    }
                    statement.execute("DROP TABLE `" + table + "`");
                }
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
                return null;
            } catch (java.sql.SQLException | RuntimeException | Error error) {
                try (var restore = connection.createStatement()) {
                    restore.execute("SET FOREIGN_KEY_CHECKS=1");
                }
                throw error;
            }
        });
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required; real MySQL 8 acceptance never skips");
        }
        return value;
    }
}
