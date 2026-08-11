package edu.bu.archive.application.ai;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/*
 * Deliberately unconditional (no @ConditionalOnProperty) - unlike
 * AwardContextBuilder/AwardCitationValidator, this is a pure, stateless,
 * zero-cost regex utility with no AWS client, no credential resolution,
 * and no network setup, so there is no reason to gate its registration
 * behind app.ai.enabled the way those heavier, AI-generation-specific
 * beans are. This lets it be the single, centralized redaction
 * implementation shared by both the AI Summary/Questions feature
 * (still gated on app.ai.enabled via its own consumers) and Award
 * Evidence Search (gated on app.search.semantic.enabled instead, per
 * docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md section
 * 6.3) - confirmed safe: no existing test asserts this bean's presence
 * or absence is tied to app.ai.enabled (AiFeatureFlagTest only tests
 * AwardAiController/AwardAiQuestionController/AiProvider beans), and
 * AwardContextBuilder's own app.ai.enabled gate is unchanged, so AI
 * Summary/Questions behavior is unaffected.
 */
@Component
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
