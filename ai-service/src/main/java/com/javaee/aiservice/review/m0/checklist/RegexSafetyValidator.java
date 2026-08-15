package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.util.ArrayDeque;
import java.util.Deque;

public final class RegexSafetyValidator {
    private static final int MAX_PATTERN_LENGTH = 512;
    private static final int MAX_REPEAT_BOUND = 1000;

    private RegexSafetyValidator() {
    }

    public static void requirePublishable(FileNameRule rule) {
        if (rule == null) {
            throw invalid("File-name rule is required");
        }
        if (rule.kind() == FileNameRuleKind.NONE) {
            if (rule.pattern() != null || rule.caseSensitive() != null) {
                throw invalid("NONE file-name rules cannot define pattern or caseSensitive");
            }
            return;
        }
        if (rule.caseSensitive() == null) {
            throw invalid("EXACT and REGEX file-name rules require explicit caseSensitive");
        }
        if (rule.kind() != FileNameRuleKind.REGEX) {
            return;
        }
        String pattern = rule.pattern();
        if (pattern == null || pattern.isBlank() || pattern.length() > MAX_PATTERN_LENGTH) {
            throw invalid("REGEX file-name rule exceeds the safe publication boundary");
        }
        if (containsUnsupportedConstruct(pattern) || containsUnsafeRepeat(pattern)) {
            throw invalid("REGEX file-name rule has unsafe or timeout-prone structure");
        }
    }

    private static boolean containsUnsupportedConstruct(String pattern) {
        boolean escaped = false;
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (escaped) {
                if (Character.isDigit(current) || current == 'k') {
                    return true;
                }
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '(' && index + 1 < pattern.length() && pattern.charAt(index + 1) == '?') {
                if (index + 2 >= pattern.length() || pattern.charAt(index + 2) != ':') {
                    return true;
                }
            }
        }
        return escaped;
    }

    private static boolean containsUnsafeRepeat(String pattern) {
        Deque<GroupRisk> groups = new ArrayDeque<>();
        groups.push(new GroupRisk());
        boolean escaped = false;
        boolean inClass = false;
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '[') {
                inClass = true;
                continue;
            }
            if (current == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass) {
                continue;
            }
            if (current == '(') {
                groups.push(new GroupRisk());
                if (index + 2 < pattern.length()
                        && pattern.charAt(index + 1) == '?' && pattern.charAt(index + 2) == ':') {
                    index += 2;
                }
                continue;
            }
            if (current == '|') {
                groups.peek().alternation = true;
                continue;
            }
            if (current == ')') {
                if (groups.size() == 1) {
                    return true;
                }
                GroupRisk closed = groups.pop();
                int quantifierEnd = quantifierEnd(pattern, index + 1);
                boolean repeated = quantifierEnd > index + 1;
                if (repeated && (closed.quantified || closed.alternation)) {
                    return true;
                }
                if (repeated) {
                    groups.peek().quantified = true;
                    index = quantifierEnd - 1;
                } else if (closed.quantified) {
                    groups.peek().quantified = true;
                }
                continue;
            }
            int quantifierEnd = quantifierEnd(pattern, index);
            if (quantifierEnd > index) {
                groups.peek().quantified = true;
                index = quantifierEnd - 1;
            }
        }
        return escaped || inClass || groups.size() != 1;
    }

    private static int quantifierEnd(String pattern, int index) {
        if (index >= pattern.length()) {
            return index;
        }
        char current = pattern.charAt(index);
        if (current == '*' || current == '+' || current == '?') {
            return index + 1;
        }
        if (current != '{') {
            return index;
        }
        int close = pattern.indexOf('}', index + 1);
        if (close < 0) {
            return index;
        }
        String bounds = pattern.substring(index + 1, close);
        String[] values = bounds.split(",", -1);
        if (values.length > 2 || values[0].isBlank() || !values[0].chars().allMatch(Character::isDigit)) {
            return index;
        }
        long lower = parseRepeatBound(values[0]);
        long upper = lower;
        if (values.length == 2) {
            if (values[1].isBlank()) {
                return close + 1;
            }
            if (!values[1].chars().allMatch(Character::isDigit)) {
                return index;
            }
            upper = parseRepeatBound(values[1]);
        }
        if (lower > upper || upper > MAX_REPEAT_BOUND) {
            throw invalid("REGEX file-name rule repeat bound is unsafe");
        }
        return close + 1;
    }

    private static long parseRepeatBound(String value) {
        if (value.length() > 10) {
            throw invalid("REGEX file-name rule repeat bound is unsafe");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid("REGEX file-name rule repeat bound is unsafe");
        }
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }

    private static final class GroupRisk {
        private boolean quantified;
        private boolean alternation;
    }
}
