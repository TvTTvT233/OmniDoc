package com.javaee.aiservice.memory.summary;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public final class SummaryInputSanitizer {

    private static final String REDACTED = "[REDACTED_SENSITIVE_VALUE]";
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+\\-/]+=*"),
            Pattern.compile("(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|approval[_-]?token|password|secret)"
                    + "\\b\\s*[:=]\\s*[^\\s,;]+"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
            Pattern.compile("\\b(sk|pk|ak)[-_][A-Za-z0-9]{16,}\\b", Pattern.CASE_INSENSITIVE));

    public String sanitize(String input) {
        String result = input == null ? "" : input;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED);
        }
        return result;
    }

    public boolean containsSensitiveValue(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        return input.contains(REDACTED)
                || SENSITIVE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(input).find());
    }
}
