package com.javaee.aiservice.memory.context;

import org.springframework.stereotype.Component;

@Component
public final class ConservativeTokenEstimator implements TokenEstimator {

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int asciiLike = 0;
        int highCost = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint > 0x7f || Character.isSurrogate((char) codePoint)) {
                highCost++;
            } else {
                asciiLike++;
            }
        }
        return highCost + (int) Math.ceil(asciiLike / 4.0d);
    }
}
