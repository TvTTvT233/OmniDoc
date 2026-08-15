package com.javaee.aiservice.review.m0.review;

public interface SafeRegexMatcher {
    boolean matches(String pattern, boolean caseSensitive, String value);
}
