package edu.bu.archive.application.ai;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class SensitiveFieldRedactor {

    private static final String REDACTED = "[REDACTED]";

    private final List<Pattern> sensitivePatterns = List.of(
            Pattern.compile(
                    "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
            ),
            Pattern.compile(
                    "(?<!\\d)\\+?\\d[\\d(). -]{7,}\\d(?!\\d)"
            ),
            Pattern.compile(
                    "(?i)\\b(?:password|passwd|secret|api[_-]?key|token)"
                            + "\\s*[:=]\\s*\\S+"
            ),
            Pattern.compile(
                    "(?i)jdbc:[a-z0-9]+://\\S+"
            ),
            Pattern.compile(
                    "(?i)\\bAKIA[0-9A-Z]{16}\\b"
            ),
            Pattern.compile(
                    "(?i)(X-Amz-(?:Signature|Credential|Security-Token))"
                            + "=[^&\\s]+"
            )
    );

    public String redact(String value) {
        if (value == null) {
            return null;
        }

        String redacted = value;
        for (Pattern pattern : sensitivePatterns) {
            redacted = pattern.matcher(redacted).replaceAll(REDACTED);
        }
        return redacted;
    }
}
