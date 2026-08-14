package com.javaee.aiservice.review.m0.model;

import java.util.Comparator;

public final class UnicodeCodePointComparator implements Comparator<String> {
    public static final UnicodeCodePointComparator INSTANCE = new UnicodeCodePointComparator();

    private UnicodeCodePointComparator() {
    }

    @Override
    public int compare(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            int compared = Integer.compare(leftCodePoint, rightCodePoint);
            if (compared != 0) {
                return compared;
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }
}
