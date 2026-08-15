package com.javaee.aiservice.review.m0.extraction;

import com.javaee.aiservice.review.m0.checklist.ChecklistCandidate;

import java.util.List;

@FunctionalInterface
public interface LocalChecklistCandidateExtractor {
    List<ChecklistCandidate> extract(List<OfficialBasisSegment> segments);
}
