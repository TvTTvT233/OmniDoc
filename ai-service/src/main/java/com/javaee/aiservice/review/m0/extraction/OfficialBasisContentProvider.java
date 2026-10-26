package com.javaee.aiservice.review.m0.extraction;

import java.util.List;

@FunctionalInterface
public interface OfficialBasisContentProvider {
    List<OfficialBasisSegment> loadOwned(String ownerId, String caseId, long basisRevision);
}
