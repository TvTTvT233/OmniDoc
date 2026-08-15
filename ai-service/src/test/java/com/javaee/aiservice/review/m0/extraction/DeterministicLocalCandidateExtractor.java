package com.javaee.aiservice.review.m0.extraction;

import com.javaee.aiservice.review.m0.checklist.ChecklistCandidate;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class DeterministicLocalCandidateExtractor implements LocalChecklistCandidateExtractor {
    private final Function<List<OfficialBasisSegment>, List<ChecklistCandidate>> behavior;

    DeterministicLocalCandidateExtractor(
            Function<List<OfficialBasisSegment>, List<ChecklistCandidate>> behavior
    ) {
        this.behavior = Objects.requireNonNull(behavior, "behavior");
    }

    @Override
    public List<ChecklistCandidate> extract(List<OfficialBasisSegment> segments) {
        return List.copyOf(behavior.apply(List.copyOf(segments)));
    }
}
