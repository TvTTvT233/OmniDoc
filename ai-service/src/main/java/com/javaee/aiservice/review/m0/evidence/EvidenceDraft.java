package com.javaee.aiservice.review.m0.evidence;

import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.EvidenceKind;
import com.javaee.aiservice.review.m0.model.EvidenceSide;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record EvidenceDraft(EvidenceSide side, EvidenceKind kind, Map<String, Object> value) {
    public EvidenceDraft {
        if (side == null || kind == null || value == null) {
            throw new IllegalArgumentException("Evidence draft is incomplete");
        }
        value = Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    public String refJson() {
        return CanonicalJson.write(value);
    }

    public String refDigest() {
        return CanonicalJson.sha256(value);
    }

    public String normalizedKey() {
        return side + ":" + kind + ":" + refJson();
    }
}
