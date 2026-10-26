package com.javaee.aiservice.review.m0.api;

import com.javaee.aiservice.review.m0.privacy.RemotePreviewSegment;

import java.util.List;

/**
 * Produces explicitly redacted preview segments. Production stays fail-closed until a
 * locally verified parser/redactor adapter is supplied; raw official text is never copied here.
 */
@FunctionalInterface
public interface ReviewM0RemotePreviewAssembler {
    List<RemotePreviewSegment> assembleOwned(String ownerId, String caseId, long basisRevision);
}
