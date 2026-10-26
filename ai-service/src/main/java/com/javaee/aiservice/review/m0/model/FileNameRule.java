package com.javaee.aiservice.review.m0.model;

import java.text.Normalizer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public record FileNameRule(FileNameRuleKind kind, String pattern, Boolean caseSensitive) {
    public FileNameRule {
        ModelChecks.required(kind, "kind");
        if (kind == FileNameRuleKind.NONE) {
            if (pattern != null || caseSensitive != null) {
                throw ModelChecks.invalid("NONE file-name rules require null pattern and caseSensitive");
            }
        } else {
            ModelChecks.required(pattern, "pattern", 1024);
            if (caseSensitive == null) {
                throw ModelChecks.invalid("EXACT and REGEX file-name rules require explicit caseSensitive");
            }
            pattern = Normalizer.normalize(pattern, Normalizer.Form.NFC);
            if (kind == FileNameRuleKind.REGEX) {
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException error) {
                    throw new ReviewM0Exception(
                            ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID,
                            "REGEX file-name rule is invalid",
                            error);
                }
            }
        }
    }
}
