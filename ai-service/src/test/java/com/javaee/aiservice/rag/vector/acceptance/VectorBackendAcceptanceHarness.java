package com.javaee.aiservice.rag.vector.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.rag.vector.acceptance.Qv04Fixture.DeletionCase;
import com.javaee.aiservice.rag.vector.acceptance.Qv04Fixture.ExpandedRecord;
import com.javaee.aiservice.rag.vector.acceptance.Qv04Fixture.QueryCase;
import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Provider-neutral hard-gate assertions. The same method is used for both production backends after integration.
 */
public final class VectorBackendAcceptanceHarness {

    private VectorBackendAcceptanceHarness() {
    }

    public static BackendReport exerciseBackend(
            String backendId,
            VectorBackendType expectedType,
            VectorBackend backend,
            Qv04Fixture fixture,
            boolean realImplementation,
            String evidenceLevel) {
        require(backend != null, "backend must not be null");
        require(backend.type() == expectedType,
                "backend type mismatch: expected " + expectedType + " but was " + backend.type());
        VectorBackendHealth health = backend.health();
        require(health != null && health.available(), "backend health must be available: " + backendId);
        require(health.backend() == expectedType, "health backend type mismatch: " + backendId);

        long memoryBefore = usedMemory();
        long upsertStarted = System.nanoTime();
        List<VectorRecord> records = fixture.records().stream().map(ExpandedRecord::record).toList();
        backend.upsertBatch(records);
        backend.upsertBatch(records);
        long upsertNanos = Math.max(1L, System.nanoTime() - upsertStarted);

        int tenantLeakage = 0;
        for (Qv04Fixture.ScopeDefinition scopeDefinition : fixture.definition().scopes()) {
            VectorScope scope = new VectorScope(scopeDefinition.userId(), scopeDefinition.knowledgeBaseId());
            VectorQuery inventoryQuery = new VectorQuery(
                    fixture.vectorFor("inventory-probe"),
                    100,
                    VectorFilter.forKnowledgeBase(scope.userId(), scope.knowledgeBaseId()),
                    fixture.embedding());
            List<VectorMatch> matches = backend.search(inventoryQuery);
            Set<String> expectedIds = fixture.recordsForScope(scope).stream()
                    .map(item -> item.record().id())
                    .collect(Collectors.toSet());
            Set<String> actualIds = matches.stream().map(VectorMatch::id).collect(Collectors.toSet());
            require(matches.size() == actualIds.size(), "idempotent upsert produced duplicate logical IDs: " + backendId);
            tenantLeakage += validateScope(matches, inventoryQuery.filter(), fixture.recordsById());
            require(actualIds.equals(expectedIds),
                    "scope inventory differs after idempotent upsert for " + scope + ": " + backendId);
        }

        List<Long> queryLatenciesMicros = new ArrayList<>();
        List<Double> recalls = new ArrayList<>();
        List<Double> reciprocalRanks = new ArrayList<>();
        int noAnswerReturned = 0;
        for (QueryCase queryCase : fixture.queries()) {
            long started = System.nanoTime();
            List<VectorMatch> matches = backend.search(queryCase.query());
            queryLatenciesMicros.add(Math.max(0L, (System.nanoTime() - started) / 1_000L));
            tenantLeakage += validateScope(matches, queryCase.query().filter(), fixture.recordsById());
            List<String> actualIds = matches.stream().map(VectorMatch::id).toList();
            if (queryCase.noAnswer()) {
                require(queryCase.expectedIds().isEmpty(), "no-answer case must not declare relevant IDs");
                noAnswerReturned += matches.size();
            } else {
                recalls.add(recallAtK(queryCase.expectedIds(), actualIds, queryCase.query().topK()));
                reciprocalRanks.add(reciprocalRank(queryCase.expectedIds(), actualIds));
            }
        }
        require(tenantLeakage == 0, "tenant leakage must be zero for " + backendId + " but was " + tenantLeakage);

        int deleteResidue = 0;
        for (DeletionCase deletion : fixture.deletions()) {
            if (deletion.mode() == Qv04Fixture.DeleteMode.DOCUMENT) {
                long deleted = backend.deleteByDocument(deletion.documentId(), deletion.scope());
                require(deleted == deletion.expectedIds().size(),
                        "document delete count mismatch for " + deletion.id() + ": " + backendId);
                VectorQuery residueQuery = new VectorQuery(
                        fixture.recordsById().get(deletion.expectedIds().get(0)).record().vector(),
                        100,
                        VectorFilter.forDocument(
                                deletion.scope().userId(), deletion.scope().knowledgeBaseId(), deletion.documentId()),
                        fixture.embedding());
                deleteResidue += backend.search(residueQuery).size();
            } else {
                String id = deletion.expectedIds().get(0);
                backend.deleteById(id, deletion.scope());
                ExpandedRecord deletedRecord = fixture.recordsById().get(id);
                VectorQuery residueQuery = new VectorQuery(
                        deletedRecord.record().vector(),
                        100,
                        VectorFilter.forKnowledgeBase(
                                deletion.scope().userId(), deletion.scope().knowledgeBaseId()),
                        fixture.embedding());
                deleteResidue += (int) backend.search(residueQuery).stream()
                        .filter(match -> match.id().equals(id))
                        .count();
            }
        }
        require(deleteResidue == 0, "delete residue must be zero for " + backendId + " but was " + deleteResidue);

        long memoryAfter = usedMemory();
        double seconds = upsertNanos / 1_000_000_000.0d;
        double throughput = (records.size() * 2.0d) / seconds;
        return new BackendReport(
                backendId,
                expectedType.configValue(),
                realImplementation,
                evidenceLevel,
                records.size(),
                fixture.embedding().dimension(),
                fixture.queries().size(),
                average(recalls),
                average(reciprocalRanks),
                tenantLeakage,
                deleteResidue,
                noAnswerReturned,
                percentile(queryLatenciesMicros, 0.50d),
                percentile(queryLatenciesMicros, 0.95d),
                throughput,
                Math.max(0L, memoryAfter - memoryBefore));
    }

    public static LifecycleReport exerciseLifecycle(
            VectorBackendLifecycle lifecycle,
            Qv04Fixture fixture) {
        VectorScope scope = new VectorScope("qv04-lifecycle-user", "qv04-lifecycle-kb");
        VectorRecord probe = new VectorRecord(
                "qv04-lifecycle-record",
                "qv04-lifecycle-document",
                fixture.vectorFor("qv04-lifecycle-probe"),
                scope,
                fixture.embedding(),
                Map.of("fixtureId", fixture.definition().fixtureId(), "synthetic", true));
        VectorQuery query = new VectorQuery(
                probe.vector(),
                5,
                VectorFilter.forKnowledgeBase(scope.userId(), scope.knowledgeBaseId()),
                fixture.embedding());

        lifecycle.firstClient().upsert(probe);
        boolean secondClientVisible = contains(lifecycle.secondClient().search(query), probe.id());
        require(secondClientVisible, "second independent client did not observe the upserted Point");
        long restartStarted = System.nanoTime();
        VectorBackend restarted = lifecycle.restartAndGetClient();
        long restartMillis = Math.max(0L, (System.nanoTime() - restartStarted) / 1_000_000L);
        boolean restartVisible = contains(restarted.search(query), probe.id());
        require(restartVisible, "record was not visible after backend restart");
        restarted.deleteById(probe.id(), scope);
        require(!contains(restarted.search(query), probe.id()), "lifecycle probe delete left residue");
        return new LifecycleReport(secondClientVisible, restartVisible, restartMillis);
    }

    public static double recallAtK(List<String> expectedIds, List<String> actualIds, int k) {
        if (expectedIds.isEmpty()) {
            throw new IllegalArgumentException("Recall@K is undefined for an empty relevance set");
        }
        Set<String> expected = new HashSet<>(expectedIds);
        long hits = actualIds.stream().limit(k).filter(expected::contains).distinct().count();
        return hits / (double) expected.size();
    }

    public static double reciprocalRank(List<String> expectedIds, List<String> actualIds) {
        Set<String> expected = new HashSet<>(expectedIds);
        for (int index = 0; index < actualIds.size(); index++) {
            if (expected.contains(actualIds.get(index))) {
                return 1.0d / (index + 1.0d);
            }
        }
        return 0.0d;
    }

    public static double overlapAtK(List<String> left, List<String> right, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        Set<String> leftTop = new HashSet<>(left.stream().limit(k).toList());
        Set<String> rightTop = new HashSet<>(right.stream().limit(k).toList());
        leftTop.retainAll(rightTop);
        return leftTop.size() / (double) k;
    }

    public static long percentile(List<Long> samples, double quantile) {
        if (samples.isEmpty()) {
            return 0L;
        }
        if (quantile <= 0.0d || quantile > 1.0d) {
            throw new IllegalArgumentException("quantile must be in (0, 1]");
        }
        List<Long> sorted = samples.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index);
    }

    public static void writeReport(Path path, Object report) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write QV04 report: " + path, exception);
        }
    }

    private static int validateScope(
            List<VectorMatch> matches,
            VectorFilter filter,
            Map<String, ExpandedRecord> recordsById) {
        int leakage = 0;
        Set<String> ids = new HashSet<>();
        for (VectorMatch match : matches) {
            require(ids.add(match.id()), "backend returned duplicate logical ID: " + match.id());
            ExpandedRecord known = recordsById.get(match.id());
            if (known == null || !known.record().scope().equals(filter.scope())) {
                leakage++;
                continue;
            }
            if (filter.documentId() != null && !known.record().documentId().equals(filter.documentId())) {
                leakage++;
            }
        }
        return leakage;
    }

    private static boolean contains(List<VectorMatch> matches, String id) {
        return matches.stream().anyMatch(match -> match.id().equals(id));
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public interface VectorBackendLifecycle {
        VectorBackend firstClient();

        VectorBackend secondClient();

        VectorBackend restartAndGetClient();
    }

    public record BackendReport(
            String backendId,
            String backendType,
            boolean realImplementation,
            String evidenceLevel,
            int recordCount,
            int dimension,
            int queryCount,
            double recallAt5,
            double mrr,
            int tenantLeakage,
            int deleteResidue,
            int noAnswerReturned,
            long p50Micros,
            long p95Micros,
            double upsertRecordsPerSecond,
            long memoryDeltaBytes) {
    }

    public record LifecycleReport(
            boolean secondClientVisible,
            boolean restartPersistent,
            long restartMillis) {
    }
}
