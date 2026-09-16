package com.javaee.aiservice.rag.vector.acceptance;

import com.javaee.aiservice.rag.vector.acceptance.Qv04Fixture.ExpandedRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class Qv04FixtureTest {

    private final Qv04Fixture fixture = Qv04Fixture.load();

    @Test
    void expandsToThreeUsersTwoKnowledgeBasesEachAndTenSegmentsPerScope() {
        Map<String, Set<String>> knowledgeBasesByUser = fixture.definition().scopes().stream()
                .collect(Collectors.groupingBy(
                        Qv04Fixture.ScopeDefinition::userId,
                        Collectors.mapping(Qv04Fixture.ScopeDefinition::knowledgeBaseId, Collectors.toSet())));

        assertThat(knowledgeBasesByUser).hasSize(3);
        assertThat(knowledgeBasesByUser.values()).allSatisfy(knowledgeBases -> assertThat(knowledgeBases).hasSize(2));
        assertThat(fixture.definition().scopes()).hasSize(6);
        assertThat(fixture.records()).hasSize(60);
        assertThat(fixture.records().stream().collect(Collectors.groupingBy(ExpandedRecord::scopeRef)).values())
                .allSatisfy(records -> assertThat(records).hasSize(10));
    }

    @Test
    void vectorsAreDeterministicLocalAndHighlySimilarAcrossTenantBoundaries() {
        assertThat(fixture.definition().synthetic()).isTrue();
        assertThat(fixture.definition().remoteEmbedding()).isFalse();
        assertThat(fixture.definition().generator().algorithm()).isEqualTo("sha-256-normalized-bytes-v1");
        assertThat(fixture.vectorFor("shared-eligibility-evidence"))
                .containsExactly(fixture.vectorFor("shared-eligibility-evidence"));

        List<ExpandedRecord> shared = fixture.records().stream()
                .filter(record -> record.ordinal() == 0)
                .toList();
        assertThat(shared).hasSize(6);
        assertThat(shared.stream().map(record -> record.record().scope()).distinct()).hasSize(6);
        for (ExpandedRecord record : shared) {
            assertThat(Qv04Fixture.cosine(shared.get(0).record().vector(), record.record().vector()))
                    .isCloseTo(1.0d, within(0.000_001d));
        }
    }

    @Test
    void includesSameDocumentMultipleSegmentsNoAnswerAndDeleteScenarios() {
        assertThat(fixture.records().stream()
                .filter(record -> record.scopeRef().equals("user-a-rules"))
                .filter(record -> record.documentSlot() == 0)
                .map(record -> record.record().documentId())
                .distinct())
                .containsExactly("qv04-user-a-rules-document-00");
        assertThat(fixture.queries()).anySatisfy(query -> {
            assertThat(query.noAnswer()).isTrue();
            assertThat(query.expectedIds()).isEmpty();
        });
        assertThat(fixture.deletions()).extracting(Qv04Fixture.DeletionCase::mode)
                .containsExactlyInAnyOrder(Qv04Fixture.DeleteMode.DOCUMENT, Qv04Fixture.DeleteMode.ID);
    }

    @Test
    void queryGroundTruthNeverCrossesItsScopeOrDocumentFilter() {
        fixture.queries().stream().filter(query -> !query.noAnswer()).forEach(query -> {
            assertThat(query.expectedIds()).isNotEmpty();
            assertThat(query.expectedIds()).allSatisfy(id -> {
                ExpandedRecord expected = fixture.recordsById().get(id);
                assertThat(expected).isNotNull();
                assertThat(expected.record().scope()).isEqualTo(query.query().filter().scope());
                if (query.query().filter().documentId() != null) {
                    assertThat(expected.record().documentId()).isEqualTo(query.query().filter().documentId());
                }
            });
        });
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
