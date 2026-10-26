package com.javaee.aiservice.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static com.javaee.aiservice.consistency.IndexContracts.*;

/** Only the explicitly persisted document-reference field is exposed; no tool metadata or text is inferred. */
public final class PublicDocumentReferences {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PublicDocumentReferences() {}
    public static List<Reference> from(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 100) return List.of();
        List<Reference> result = new ArrayList<>();
        for (Object raw : list) {
            try {
                Reference ref = MAPPER.convertValue(raw, Reference.class);
                if (ref == null || !id(ref.referenceId()) || !id(ref.documentId()) || !id(ref.generationId()) || !id(ref.segmentId())
                        || ref.documentVersion() <= 0 || ref.startOffset() < 0 || ref.endOffset() <= ref.startOffset()
                        || !hashValue(ref.contentHash()) || !hashValue(ref.segmentHash()) || ref.documentTitle() == null || ref.documentTitle().length() > 1000) continue;
                result.add(ref);
            } catch (Exception ignored) { /* Malformed old metadata is not a public citation. */ }
        }
        return result.stream().distinct().toList();
    }
    private static boolean id(String value) { return value != null && value.matches("[A-Za-z0-9_.:-]{1,255}"); }
    private static boolean hashValue(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
